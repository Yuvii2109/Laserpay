# case-orchestrator-service — module context

> Port **8085**. Java package root `com.laserpay.pdei.orchestrator`.
> Implements **PLATFORM-CONTRACT.md section 10** (Temporal) precisely.
> Namespace `pdei`, task queue `pdei-dispute-cases`, workflow id `case-{caseId}`.

This file is how a future session reloads context on this module. If it disagrees with
`docs/PLATFORM-CONTRACT.md` or `docs/SHARED-LIBRARY-API.md`, those win and this file is wrong.

---

## 1. Purpose

A dispute is not a request/response. It runs for days or weeks: evidence trickles in, a human has
to look at some of them, a package is assembled and submitted, and then the network takes its time
answering. This module owns that **long-running workflow** and nothing else.

It is a **sequencer, not a decision maker**. Every judgement — readiness scoring, gap detection,
admission control, the safety gate, package assembly, dispute state transitions — belongs to
`evidence-core`. This module decides *when* those things happen, in what order, what to wait for,
what to do when a step fails, and how a human interrupts the sequence.

Three processes run in one JVM:

1. a **Temporal worker** hosting `DisputeCaseWorkflowImpl` and `CaseActivitiesImpl`;
2. a **Kafka consumer** on `pdei.dispute.events.v1` that starts and signals cases;
3. a small **internal HTTP API** under `/orchestrator/v1` plus actuator, so `api-gateway-service`
   never needs a Temporal client of its own.

---

## 2. Responsibilities

**Owns**

- the `DisputeCaseWorkflow` definition — twelve steps, four signals, two queries;
- the durable timers: the 7-day missing-evidence wait, the human-approval and escalation windows,
  the follow-up loop;
- saga-style compensation when a step fails non-retryably;
- continue-as-new so a case that lives for weeks does not accumulate an unbounded event history;
- INSERTs and workflow-owned column updates on `pdei.dispute_cases` (`CaseWriter`) — the only
  writer of that table's `workflow_id`, `run_id`, `progress_percent`, `approval_*`,
  `failure_reason`;
- publication of every `CASE` event to `pdei.case.events.v1`;
- the network-submission boundary (`NetworkSubmitter` / `SimulatedNetworkSubmitter`).

**Does not own**

- readiness, gaps, contradictions, policy, the safety gate, package assembly — `evidence-core`;
- the `disputes` table lifecycle — `DisputeService` in `evidence-core` (this module *asks* for
  transitions and accepts a refusal);
- evidence creation or versioning — `state-builder-worker`, `document-processor-service`;
- any AI reasoning — that lives only in the Python `ai-reasoning-service`, reached through
  `core.ai.AiReasoningClient`. **No AI code, prompt, provider name or SDK appears in this module.**

---

## 3. The state machine

`CasePhase` **is** the state machine. Each constant carries its contract step number (1–12) and the
progress percentage the `getProgress` query reports.

```
                       ┌──────────────────────────────────────────────┐
                       │                                              │
  CREATED              │            (cancelCase signal at any point)  │
     │                 │                                              ▼
     ▼                 │                                        CANCELLED
  OPENING              │  step 1  openCase
     │                 │
     ▼                 │
  GATHERING_EVIDENCE ◄─┼──────────────────┐  step 2  gatherEvidence
     │                 │                  │
     ▼                 │                  │
  DETECTING_GAPS       │                  │  step 3  detectGaps
     │                 │                  │
     ▼                 │                  │
  AWAITING_EVIDENCE    │                  │  step 4  timer + evidenceArrived, max 7 days
     │  (skipped when no blocking gaps)   │
     ▼                 │                  │
  ADMISSION_CONTROL    │                  │  step 5  runAdmissionControl
     │                 │                  │
     ▼                 │                  │
  INVESTIGATING        │                  │  step 6  investigate (AI or deterministic)
     │                 │                  │
     ▼                 │                  │
  GATING               │                  │  step 7  validateAndGate
     │                 │                  │
     ├── gate ALLOW + PREPARE_REPRESENTMENT ─────────────┐
     ▼                 │                  │              │
  AWAITING_APPROVAL    │                  │  step 8      │
     │                 │                  │              │
     ├── timeout ──► ESCALATED ── timeout ──► CLOSED (ESCALATION_EXPIRED)
     ├── REJECT ─────────────────────────────► CLOSED (REJECTED_BY_HUMAN / LIABILITY_ACCEPTED)
     ├── REQUEST_MORE_EVIDENCE ──────────────┘  (new assessment round, bounded)
     │
     └── APPROVE / SUBMIT ──────────────────────────────┐
                                                        ▼
                                            PREPARING_PACKAGE   step 9
                                                        │
                                                        ▼
                                            SUBMITTING          step 10
                                                        │
                                                        ▼
                                            FOLLOW_UP           step 11  timer loop
                                                        │
                                                        ▼
                                            CLOSING ► CLOSED    step 12
```

Failure at any step: compensations run in reverse, the case row is marked `FAILED`, and the
workflow **fails** (rather than returning). See section 8.

**Assessment loop.** Steps 2–8 repeat when a reviewer answers `REQUEST_MORE_EVIDENCE`, bounded by
`pdei.orchestrator.timers.max-assessment-rounds` (default 3). Exhausting it closes the case as
`EVIDENCE_INSUFFICIENT`. Step 1 and steps 9–12 run at most once.

`CaseStatus` (contract section 6) is what is persisted; `CasePhase` is the finer-grained workflow
view. Mapping:

| CasePhase | persisted CaseStatus |
|---|---|
| CREATED, OPENING | `CREATED` |
| GATHERING_EVIDENCE, DETECTING_GAPS, PREPARING_PACKAGE | `ASSEMBLING` |
| AWAITING_EVIDENCE | `AWAITING_EVIDENCE` |
| ADMISSION_CONTROL, INVESTIGATING, GATING | `INVESTIGATING` |
| AWAITING_APPROVAL, ESCALATED | `AWAITING_APPROVAL` |
| (after step 9) | `PREPARED` |
| SUBMITTING, FOLLOW_UP | `SUBMITTED` |
| CLOSED, CANCELLED | `CLOSED` |
| FAILED | `FAILED` |

---

## 4. Signals and queries (contract section 10, verbatim names)

### Signals

| Signal | Payload | What it does | Duplicate handling |
|---|---|---|---|
| `evidenceArrived` | `EvidenceArrivedSignal` | Wakes the step 4 wait, which re-runs `gatherEvidence` + `detectGaps`. Ignored (harmlessly) at any other step. | A repeated `evidenceId` does **not** wake the wait — the id set is deduplicated in the signal handler. |
| `humanDecision` | `HumanDecision` (`APPROVE` / `REJECT` / `SUBMIT` / `REQUEST_MORE_EVIDENCE`) | Releases step 8. | **First decision wins**; a second unconsumed decision is logged and dropped, so a double click cannot turn a REJECT into an APPROVE. |
| `disputeUpdated` | `DisputeUpdatedSignal` | Tracks dispute status; a terminal status ends the step 11 follow-up loop and interrupts long waits. | Deduplicated on `eventId`; a non-terminal update arriving *after* a terminal one is ignored as stale. |
| `cancelCase` | `CancelCaseSignal` | Graceful stop: the workflow finishes what it is on, runs `closeCase` with `CANCELLED`, returns normally. Compensation does **not** run — nothing went wrong. | Second cancel is a no-op. |

### Queries (both side-effect free, no database access)

| Query | Returns | Used by |
|---|---|---|
| `getCaseState` | `CaseState` — full in-memory state: statuses, phase, readiness, gaps, admission, investigation, gate verdict, human decision, package, receipt, resolution | Case X-Ray, ops |
| `getProgress` | `CaseProgress` — phase, step (n of 12), percent, completed steps, what it is waiting for | `GET /api/v1/stream/cases/{caseId}`, case-queue swimlanes |

---

## 5. Timer durations

All of them are **pinned into the workflow input** (`CaseTimers` inside `DisputeCaseInput`) when the
case starts. Workflow code may not read Spring configuration: a property change between the original
execution and a replay would make the two diverge and Temporal would reject the replay. So
**retuning affects new cases only** — a running case keeps the timings it began with.

| Timer | Property | Default | Purpose |
|---|---|---|---|
| Missing-evidence wait | `pdei.orchestrator.timers.missing-evidence-wait` | **7d** (contract cap) | Step 4 total budget |
| Evidence wait slice | `…evidence-wait-slice` | 12h | How often step 4 wakes to re-evaluate and consider continue-as-new. Does **not** extend the budget. |
| Human approval timeout | `…human-approval-timeout` | 48h | Step 8 first window; expiry emits `CaseEscalated` |
| Escalation timeout | `…escalation-timeout` | 72h | Step 8 second window; expiry closes as `ESCALATION_EXPIRED` |
| Follow-up interval | `…follow-up-interval` | 24h | Step 11 tick |
| Follow-up ceiling | `…follow-up-max-duration` | 45d | Step 11 hard stop |
| Continue-as-new threshold | `…continue-as-new-history-threshold` | 8000 events | Also honours `WorkflowInfo.isContinueAsNewSuggested()` |
| Max assessment rounds | `…max-assessment-rounds` | 3 | How often steps 2–8 may repeat |
| Workflow execution timeout | `pdei.orchestrator.workflow-execution-timeout` | 90d | End to end, across all continue-as-new generations |
| Workflow task timeout | `pdei.orchestrator.workflow-task-timeout` | 20s | One slice of decision work |

The `local` Spring profile in `application.yml` shrinks these to minutes/seconds so a full
twelve-step case is demonstrable by hand.

**Activity retry policy** (contract section 10, verbatim): initial 1s, backoff 2.0, max interval
60s, max attempts 10. Non-retryable: `com.laserpay.pdei.common.error.PolicyViolationException` and
`…ValidationException` — registered by fully-qualified class name in
`DisputeCaseWorkflowImpl.ACTIVITY_RETRY_OPTIONS`. Two stubs share that policy: a 2-minute
start-to-close for reads/scoring/events, and a 15-minute one for package assembly and submission,
which stream objects to and from MinIO.

---

## 6. File-by-file map

### `orchestrator` (root)

| File | Role |
|---|---|
| `CaseOrchestratorApplication.java` | Spring Boot entry point. `@EnableKafka`; everything else auto-configures. |

### `orchestrator.workflow` — deterministic workflow code

| File | Role |
|---|---|
| `DisputeCaseWorkflow.java` | `@WorkflowInterface`. `run(DisputeCaseInput)`, four `@SignalMethod`s, two `@QueryMethod`s. Also carries the `NAMESPACE`, `TASK_QUEUE` and `WORKFLOW_TYPE` constants. |
| `DisputeCaseWorkflowImpl.java` | The twelve steps, the assessment loop, the three waits, saga compensation, continue-as-new, signal handlers, query projections. ~980 lines and the heart of the module. |

### `orchestrator.activity` — everything that touches the world

| File | Role |
|---|---|
| `CaseActivities.java` | `@ActivityInterface` with the ten contract activities. |
| `CaseActivitiesImpl.java` | `@Component @ActivityImpl(taskQueues = …)`. Delegates to `evidence-core`; writes case rows; publishes CASE events; audits. |
| `ActivityMemo.java` | Redis-backed memoisation for the four activities that are not naturally idempotent. Degrades to `recomputeAlways` without Redis. |

### `orchestrator.model` — records on the workflow boundary

Workflow input/output and state: `DisputeCaseInput`, `CaseTimers`, `CaseCarryOver`, `CasePhase`,
`CaseResolution`, `CaseState`, `CaseProgress`, `CaseOutcome`, `CaseRef`.
Signal payloads: `EvidenceArrivedSignal`, `HumanDecision`, `HumanDecisionType`,
`DisputeUpdatedSignal`, `CancelCaseSignal`.
Activity requests/results: `OpenCaseRequest`/`OpenCaseResult`, `EvidenceReport`, `GapReport`,
`AdmissionOutcome`, `InvestigationRequest`/`InvestigationOutcome`, `GateRequest`/`GateOutcome`,
`PreparePackageRequest`/`PackageResult`, `SubmitRequest`/`SubmissionReceipt`,
`CloseCaseRequest`/`CloseCaseResult`, `CaseEventCommand`.

All immutable records; money is always `Money(long amountMinor, String currency)`; every timestamp
is `Instant`.

### `orchestrator.listener` — Kafka in

| File | Role |
|---|---|
| `DisputeEventListener.java` | `pdei.dispute.events.v1` → workflow start / signal. Dedupes on `processed_events`. |
| `CaseIdResolver.java` | `disputeId` → the one `caseId` it may ever have. Adopts an existing row; otherwise derives `CASE-` + 12 hex chars of `sha256(disputeId)`. |
| `DeadLetterPublisher.java` | `DeadLetterEnvelope` → `pdei.dlq.v1` after the retry budget is spent. |

### `orchestrator.signal` — Temporal client

| File | Role |
|---|---|
| `CaseSignalService.java` | The only Temporal client in the platform: start, four signals, two queries, terminate, describe. Every method tolerates a missing workflow. |
| `CaseWorkflowDescription.java` | Flattened `DescribeWorkflowExecution` response. |

### `orchestrator.api` — internal ops surface

| File | Role |
|---|---|
| `OrchestratorController.java` | `/orchestrator/v1` — signal, query, terminate, describe. |
| `SignalRequest`, `DecisionRequest`, `SignalAck` | Request/response DTOs. |
| `OrchestratorExceptionHandler.java` | `PdeiException` → `ErrorResponse` with its own `code` and HTTP status. |

### `orchestrator.persistence`

| File | Role |
|---|---|
| `CaseWriter.java` | The only writer of `pdei.dispute_cases`. `INSERT … ON CONFLICT DO NOTHING`, forward-only progress, first-write-wins timestamps. Resolves the primary-key column name at runtime (see Known gaps). |
| `CaseRow.java` | Narrow read view of the workflow-owned columns. |

### `orchestrator.submission`

| File | Role |
|---|---|
| `NetworkSubmitter.java` | The port. Contract: idempotent on `(caseId, packageVersion, bundleSha256)`. |
| `SimulatedNetworkSubmitter.java` | The only implementation. Deterministic, clearly named, always sets `simulated = true`. |
| `NetworkSubmissionRequest` / `NetworkSubmissionResult` | Its records. |

### `orchestrator.config`

| File | Role |
|---|---|
| `TemporalConfig.java` | Data converter (`Json.mapper()`), worker tuning, worker-factory cache sizing, the pinned `CaseTimers` bean, and the `WorkflowOptions` used to start a case. |
| `OrchestratorProperties.java` | `pdei.orchestrator.*`. |
| `KafkaConfig.java` | String consumer + JSON producer, manual acks, exponential backoff then DLQ. |

### `src/test`

| File | Role |
|---|---|
| `workflow/DisputeCaseWorkflowTest.java` | Five `TestWorkflowEnvironment` scenarios (section 12). |
| `support/FakeCaseActivities.java` | Stateful hand-written activity double. |
| `DeterministicIdentityTest.java` | Case id, event id, submission reference, timer fallbacks, phase progress. |

---

## 7. Idempotency — four independent layers

Contract rule 9 ("all consumers tolerate duplicates") and rule 10 ("assume late and out-of-order
events") are enforced at four levels, deliberately overlapping:

1. **Postgres claim.** `ProcessedEventRepository.markProcessed(eventId, "pdei-case-orchestrator-service")`.
   The claim is written **after** handling, not before: a crash in between causes a redelivery, and
   a redelivery is harmless, whereas claiming first could mark an event handled that never reached
   Temporal.
2. **Deterministic workflow id + reuse policy.** `case-{caseId}` where `caseId = f(disputeId)`, plus
   `WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY`. A duplicate `DisputeCreated` while the
   case runs, or after it completed, is rejected by Temporal and swallowed by the listener. After a
   *failed* run it is allowed — which is exactly the duplicate worth acting on, and gives free
   crash recovery from a topic replay.
3. **Signal handlers.** Repeated evidence ids and repeated dispute `eventId`s are dropped inside the
   workflow; the human decision is first-wins.
4. **Activity level.** Reads are naturally repeatable and writes are whole-value upserts. The four
   activities that are not naturally idempotent — `runAdmissionControl` (spends a Redis budget
   token), `investigate` (mints an id, may spend a model call), `validateAndGate` (appends to the
   audit chain), `prepareRepresentmentPackage` (writes a new package version) — receive a
   deterministic token from the workflow (`{caseId}:r{round}:{label}`) and run through
   `ActivityMemo`.

**Published events are idempotent too.** `CaseActivitiesImpl.deterministicEventId` derives the
`eventId` as a UUIDv3 over the idempotency key, so an activity retry republishes a byte-identical
event and every downstream consumer dedupes it for free.

---

## 8. Failure handling and compensation

Compensations are registered as the workflow acquires state:

| After | Compensation |
|---|---|
| step 1 `openCase` | `closeCase` with `CaseResolution.FAILED`, `CaseStatus.FAILED` |
| step 9 `prepareRepresentmentPackage` | `CaseEscalated` naming the bundle key and hash — a signed package cannot be un-assembled, so the honest compensation is to put it in front of a human |
| step 10 `submitRepresentment` | `CaseEscalated` asking for manual reconciliation — a submission cannot be un-sent |

On failure the saga compensates in reverse (`continueWithError = true`, so one failing compensation
does not abort the rest), the case row is marked `FAILED` with a truncated failure reason, and the
workflow **fails** via `Workflow.wrap(...)`. Failing rather than returning is deliberate: it shows
red in the Temporal UI, increments `pdei_workflow_failures_total{workflow="DisputeCaseWorkflow"}`,
and lets the reuse policy restart the case from a later redelivery.

Temporal-level cancellation (`CanceledFailure`, distinct from the `cancelCase` signal) closes the
case inside `Workflow.newDetachedCancellationScope` so the closing activity is not cancelled along
with everything else.

---

## 9. Determinism rules the workflow obeys

- no `Instant.now()` / `System.currentTimeMillis()` — time is `Workflow.currentTimeMillis()`;
- no randomness at all (if any were needed it would be `Workflow.newRandom()`);
- no database, HTTP, MinIO, Kafka or Spring bean access — every side effect is an activity;
- no configuration reads — durations arrive pinned in `CaseTimers`;
- logging through `Workflow.getLogger`, which suppresses duplicate lines during replay;
- the workflow carries only *flattened summaries* of activity results, never large payloads, which
  is what keeps continue-as-new cheap.

**Continue-as-new** fires only from inside the two genuinely long waits (step 4 and step 11), when
`isContinueAsNewSuggested()` or the configured history threshold trips. `CaseCarryOver` carries the
elapsed budget of both waits, the follow-up tick counter, the assessment round, and the package and
receipt — so a continued generation never restarts a clock or redoes work.

---

## 10. Inbound contracts (what this module consumes)

### Kafka

| Topic | Group | Events acted on |
|---|---|---|
| `pdei.dispute.events.v1` | `pdei-case-orchestrator-service` | `DisputeCreated` → start workflow; `DisputeUpdated`, `DisputeClosed` → `disputeUpdated` signal. Anything else is ignored, not dead-lettered. |

The listener re-reads the dispute from Postgres (`DisputeService.find`) rather than trusting the
payload; the payload is a fallback only for the window where the producer's row is not yet
committed. A dispute that is readable from neither raises a retryable failure, so the container
backs off and eventually dead-letters.

### HTTP (called by `api-gateway-service`)

Base `http://case-orchestrator-service:8085/orchestrator/v1`:

```
POST /cases/{caseId}/signal                  {signal, decision?, actor?, notes?, evidenceId?,
                                              evidenceType?, disputeStatus?, eventId?, reason?}
POST /cases/{caseId}/approve                 {actor, notes}     <- /api/v1/cases/{id}/approve
POST /cases/{caseId}/reject                  {actor, notes}     <- /api/v1/cases/{id}/reject
POST /cases/{caseId}/submit                  {actor, notes}     <- /api/v1/cases/{id}/submit
POST /cases/{caseId}/request-more-evidence   {actor, notes}
POST /cases/{caseId}/evidence-arrived        {evidenceId, evidenceType, eventId}
POST /cases/{caseId}/cancel                  {reason, actor}
GET  /cases/{caseId}/state                   -> CaseState
GET  /cases/{caseId}/progress                -> CaseProgress
GET  /cases/{caseId}/query?name=getCaseState|getProgress
POST /cases/{caseId}/terminate               {reason, actor}
GET  /cases/{caseId}/describe                -> CaseWorkflowDescription
```

Signal routes answer `200` with `SignalAck{delivered:false}` when no workflow is running — that is
a race a caller cannot avoid, not a server error.

Actuator: `/actuator/health`, `/actuator/health/readiness`, `/actuator/prometheus`,
`/actuator/metrics`, `/actuator/loggers`.

### Postgres tables read/written

| Table | Access |
|---|---|
| `pdei.dispute_cases` | **INSERT + UPDATE** (`CaseWriter`) — the only writer |
| `pdei.disputes` | read via `DisputeService`; transitions requested through it |
| `pdei.case_evidence` | replaced each gather via `CaseRepositoryPort.replaceCaseEvidence` |
| `pdei.investigations` | written via `CaseRepositoryPort.saveInvestigation` |
| `pdei.ai_admission_log` | appended by `AdmissionController` |
| `pdei.audit_events` | appended by `AuditRecorder` |
| `pdei.processed_events` | consumer dedupe claim |
| `pdei.evidence`, `pdei.policies`, `pdei.readiness_*`, transaction tables | read through `evidence-core` |

### Redis

| Key | Purpose |
|---|---|
| `pdei:case:{caseId}:memo:{token}` | `ActivityMemo` results, TTL 24h |
| `pdei:ai:budget:{date}`, `pdei:ai:bucket` | consumed indirectly by `AdmissionController` |

### MinIO

Reads `pdei-packages` (bundle presence check), writes the submission receipt (section 11).

---

## 11. Outbound contracts (what this module produces)

### `pdei.case.events.v1` — `CanonicalEvent`, `aggregateType = CASE`, `aggregateId = caseId`

| EventType | Emitted by | Payload highlights |
|---|---|---|
| `CaseOpened` | `openCase` activity | disputeId, transactionId, reasonCode, amountMinor+currency, workflowId, adopted |
| `CaseEvidenceAttached` | workflow, after each gather | evidenceCount, usableEvidenceCount, readinessScore, readinessBand, assessmentRound |
| `CaseInvestigated` | workflow, after step 6 | investigationId, classification, confidence, recommendedAction, aiUsed, provider |
| `CaseEscalated` | workflow, on approval timeout and as compensation | reason, gateDecision, escalationWindow — or the compensation marker |
| `CasePrepared` | workflow, after step 9 | manifestId, packageVersion, bundleObjectKey, bundleSha256, itemCount, readinessScore |
| `CaseSubmitted` | workflow, after step 10 **and on each follow-up tick** | submissionId, networkReference, submitter, simulated, packageVersion, bundleSha256 / `{phase:"FOLLOW_UP", tick:n}` |
| `CaseClosed` | `closeCase` activity | resolution, caseStatus, disputeStatus, reason, compensating |

Partition key is always `merchantId + ":" + aggregateId`. `eventId` is derived deterministically
from the idempotency key. Phases with no matching CASE type in contract section 3.1
(`AWAITING_EVIDENCE`, `AWAITING_APPROVAL`) persist status only and publish nothing — no enum value
was invented for them.

Follow-up ticks reuse `CaseSubmitted` with a `phase: "FOLLOW_UP"` payload marker rather than
inventing a type; each tick's idempotency key carries the tick number, so ticks never collide.

### `pdei.audit.events.v1`

Via `AuditRecorder`: `CASE_OPENED`, `CASE_SUBMITTED`, `CASE_CLOSED` / `CASE_COMPENSATED`,
`INVESTIGATION_AI` / `INVESTIGATION_DETERMINISTIC`, plus the `SAFETY_GATE_*` entries `SafetyGate`
writes itself.

### `pdei.dlq.v1`

`DeadLetterEnvelope` after five attempts with exponential backoff (1s → 30s cap).

### MinIO — submission receipt

`prepareRepresentmentPackage` writes the bundle and `manifest.json` through
`CaseAssemblyService` at the contract section 11 keys. `submitRepresentment` then verifies the
bundle exists and writes a third object next to them:

```
pdei-packages/{merchantId}/{caseId}/submission-{caseId}-v{n}.json
```

This key shape is an **extension** of contract section 11 (which names only the bundle and the
manifest). It exists so a submission is reconstructable from object storage alone, exactly like the
manifest. If the contract is ever tightened, this is the line to change.

### Metrics (contract section 13)

| Metric | Where |
|---|---|
| `pdei_events_processed_total{service,type,outcome}` | listener |
| `pdei_events_duplicate_total{service}` | listener |
| `pdei_event_processing_latency_seconds{service,type}` | listener |
| `pdei_workflow_failures_total{workflow}` | `closeCase` on a FAILED close |
| `pdei_case_assembly_seconds` | `CaseAssemblyService` (evidence-core) |
| `pdei_ai_admission_total{decision}`, `pdei_policy_gate_total{decision}` | evidence-core, driven from here |
| `pdei_case_submissions_total{submitter,outcome}` | `SimulatedNetworkSubmitter` — **module-local, not in contract section 13** |

---

## 12. Tests

`src/test/java/.../workflow/DisputeCaseWorkflowTest.java` runs five scenarios against
`TestWorkflowEnvironment` with the **production data converter**, so it also exercises the
round-tripping of `Money`, `Instant`, `Duration` and every enum on the workflow boundary. Time is
skipped, not slept: the 7-day wait and the 48-hour approval window pass in milliseconds while the
workflow experiences them at full length.

| Test | Asserts |
|---|---|
| `happyPath` | All twelve steps in order, exactly one package and one submission, step 4 skipped, no `CaseEscalated`, resolution `SUBMITTED_AWAITING_OUTCOME` |
| `missingEvidenceThenSignal` | Step 4 parks in `AWAITING_EVIDENCE`; `evidenceArrived` re-runs steps 2–3; a duplicate `evidenceId` does **not** wake it twice; case proceeds and submits |
| `humanRejection` | Gate `ALLOW_WITH_REVIEW` → step 8 → `REJECT` → nothing is prepared or submitted, dispute asked to move to `LOST` |
| `aiDeniedBySafetyGate` | Gate `DENY` → never prepared or submitted whatever the AI claimed; escalation emitted; both windows expire → `ESCALATION_EXPIRED` |
| `queriesAndTerminalDispute` | `getProgress`/`getCaseState` report the live phase while parked; a terminal `disputeUpdated` ends follow-up → `SUBMITTED_AND_RESOLVED` |

`DeterministicIdentityTest` covers the pure functions the duplicate-tolerance story rests on: case
id derivation, deterministic event ids, submission-reference stability across retries, refusal of an
unverifiable package, timer fallbacks, and phase progress monotonicity.

Query assertions are captured inside `registerDelayedCallback` and asserted **after** the run —
an assertion failing on a Temporal-managed thread would not fail the test.

---

## 13. Configuration and environment variables

Contract section 15 names, all with dev defaults in `application.yml`:

| Variable | Used for |
|---|---|
| `PDEI_TEMPORAL_TARGET` | `spring.temporal.connection.target` (default `localhost:7233`) |
| `PDEI_TEMPORAL_NAMESPACE` | fixed to `pdei`; the namespace is a contract constant, not tunable |
| `PDEI_POSTGRES_URL` / `_USER` / `_PASSWORD` | case rows, dispute reads, idempotency claims |
| `PDEI_KAFKA_BOOTSTRAP` | dispute events in, case + audit + DLQ events out |
| `PDEI_REDIS_URL` | `ActivityMemo`, AI budget gate |
| `PDEI_MINIO_ENDPOINT` / `_ACCESS_KEY` / `_SECRET_KEY` | package bundle + receipt |
| `PDEI_AI_SERVICE_URL`, `PDEI_SERVICE_TOKEN` | `HttpAiReasoningClient` in evidence-core |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | tracing |
| `SPRING_PROFILES_ACTIVE=local` | shrinks every timer for demos |

Module-specific properties live under `pdei.orchestrator.*` (section 5) and
`pdei.orchestrator.worker.*` (20 workflow-task slots, 60 activity slots, 200-execution sticky cache
— activities outnumber workflow tasks in this workload because a case spends most of its life
asleep).

`spring.flyway.enabled=false` on purpose: `platform-persistence` owns migrations, and twelve
services racing to migrate on startup is a bad idea.

---

## 14. Dependencies on other modules

| Module | Used for |
|---|---|
| `platform-common` | `CanonicalEvent`, `EventType`, `Money`, `Ids`, `IdPrefix`, `Json`, `Hashes`, `Topics`, `ConsumerGroups`, `MetricNames`, `Clocks`, the `PdeiException` hierarchy |
| `platform-persistence` | `ProcessedEventRepository`, the DataSource/JPA auto-configuration, the Flyway schema this module writes into |
| `evidence-core` | `ReadinessEngine`, `PolicyEngine`, `AdmissionController`, `AiReasoningClient`, `DeterministicInvestigator`, `SafetyGate`, `CaseAssemblyService`, `DisputeService`, `TimelineService`, `AuditRecorder`, `ObjectStore`, `Buckets`, `CaseRepositoryPort`, `EvidenceRepositoryPort`, `EventPublisherPort` |

Consumed **by**: `api-gateway-service` (HTTP), `audit-service` and `api-gateway-service`
(`pdei.case.events.v1`), the frontend indirectly.

External: `io.temporal:temporal-sdk` + `temporal-spring-boot-starter-alpha` (version pinned to the
reactor's `${temporal.version}`), `spring-boot-starter-web`, `spring-kafka`,
`micrometer-registry-prometheus`.

---

## 15. How to build and run

```bash
# from the repo root
cd backend

# this module and its three reactor dependencies
mvn -pl case-orchestrator-service -am clean install

# tests only (TestWorkflowEnvironment is in-process: no Temporal server needed)
mvn -pl case-orchestrator-service test

# run against local infra (Postgres, Kafka, Redis, MinIO, Temporal must be up)
mvn -pl case-orchestrator-service spring-boot:run -Dspring-boot.run.profiles=local
```

Docker (build context is the **repo root**, not this directory):

```bash
docker build -f backend/case-orchestrator-service/Dockerfile -t pdei/case-orchestrator-service .
docker run --network pdei-net -p 8085:8085 \
  -e PDEI_TEMPORAL_TARGET=temporal:7233 \
  -e PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
  -e PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
  -e PDEI_REDIS_URL=redis://redis:6379 \
  -e PDEI_MINIO_ENDPOINT=http://minio:9000 \
  pdei/case-orchestrator-service
```

Smoke test:

```bash
curl localhost:8085/actuator/health
curl localhost:8085/orchestrator/v1/cases/CASE-XXXX/progress
curl -X POST localhost:8085/orchestrator/v1/cases/CASE-XXXX/approve \
  -H 'content-type: application/json' -d '{"actor":"ops@laserpay.test","notes":"looks good"}'
```

The Temporal UI at `localhost:8233` shows every execution; the workflow id is `case-{caseId}`.

---

## 16. Extension points

| Want to… | Do this |
|---|---|
| Submit to a real PSP | Implement `NetworkSubmitter` and register that bean; nothing else changes. Keep the idempotency contract on `(caseId, packageVersion, bundleSha256)`. |
| Add a workflow step | Add the activity to `CaseActivities`, implement it, add a `CasePhase` constant with its step number, and call it from `DisputeCaseWorkflowImpl`. **Changing the order of existing steps breaks replay of in-flight cases** — use `Workflow.getVersion` for a compatible change. |
| Change a timer | `pdei.orchestrator.timers.*`. New cases only, by design. |
| Add a signal | Add a `@SignalMethod`, a payload record, a `CaseSignalService` method and a controller route. Handle duplicates in the handler. |
| Route decisions differently (e.g. auto-submit above a readiness score) | This is a **policy** decision: extend `PolicyEngine`/`SafetyGate` in `evidence-core` and let `GateOutcome.autoApproved()` reflect it. Do not put thresholds in workflow code. |
| Expose more case state to the UI | Add fields to `CaseState`/`CaseProgress`; both are pure projections of workflow fields. |
| Replace the memo store | `ActivityMemo` is a single class with one `remember` method. |

---

## 17. Known gaps and TODOs

1. **P0 — `dispute_cases` / `disputes` primary-key column divergence.**
   `platform-persistence`'s Flyway migration `V5__disputes.sql` names the primary keys `case_id` and
   `dispute_id` and the manifest column `package_manifest`. `evidence-core`'s
   `spi/jdbc/JdbcCaseRepository` queries columns named `id` and `manifest_json` (and
   `JdbcEvidenceRepository` likewise queries `pdei.evidence.id` against a schema whose column is
   `evidence_id`). One of the two is wrong and it will surface at the first real query.
   The contract says migrations are owned by `platform-persistence`, so the **Flyway names are
   normative**; `evidence-core`'s SQL should be corrected. This module's `CaseWriter` targets the
   Flyway names but resolves the actual column once from `information_schema.columns` and caches it,
   so the orchestrator works against whichever schema is deployed. That fallback is a bridge, not a
   fix — remove it once the divergence is resolved in one place.
2. **Real PSP / card-network submission is out of scope.** `SimulatedNetworkSubmitter` is the only
   `NetworkSubmitter`. It never contacts a network, and every receipt it produces carries
   `simulated = true` all the way to the UI and the audit trail.
3. **`ActivityMemo` without Redis degrades to recompute-always.** Retry idempotency for
   `runAdmissionControl`, `investigate`, `validateAndGate` and `prepareRepresentmentPackage` is
   then best-effort: a retry could spend a second budget token or produce a second package version.
   A durable memo table would remove the caveat; it would need a migration in
   `platform-persistence`.
4. **The internal API has no authentication.** `/orchestrator/v1` trusts its caller. It must not be
   exposed outside the Docker network. The service-token header used by the AI tool endpoints
   (`X-PDEI-Service-Token`) is the obvious mechanism to adopt.
5. **`detectGaps` recomputes readiness** that `gatherEvidence` just computed. Deterministic and
   cheap, but two full computations per round. If it shows up in `pdei_readiness_computation_seconds`,
   pass the snapshot between the two activities or cache it on `pdei:readiness:{transactionId}`.
6. **`openCase` transitions the dispute to `EVIDENCE_GATHERING`, which publishes `DisputeUpdated`,
   which this same listener consumes and turns into a `disputeUpdated` signal.** The loop is bounded
   and idempotent (non-terminal status, deduped on `eventId`) but it is real extra traffic. Worth a
   `source`-based filter if the topic gets busy.
7. **No `pdei_kafka_consumer_lag` gauge** is registered here; it is expected from a shared Kafka
   metrics binder that does not exist yet.
8. **Follow-up ticks reuse `CaseSubmitted`.** There is no `CaseFollowUp` in the contract's CASE
   enum and none was invented. If the UI needs to distinguish them beyond the `phase` payload
   marker, the enum in `platform-common` is the place to change — not this module.
9. **No integration test against a real Temporal server** (or Testcontainers). The workflow logic is
   covered in-process; the Spring wiring, the Kafka listener and `CaseWriter`'s SQL are not yet
   covered by an automated test.
10. **`describe` maps only the fields the ops UI needs.** Pending activities, retry state and the
    search attributes in `DescribeWorkflowExecutionResponse` are not surfaced.
11. **`terminate` leaves the case row untouched** (that is the point of terminate vs. cancel), so an
    operator who uses it must fix the row by hand. `POST /cancel` is almost always the right call.
