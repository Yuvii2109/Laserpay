# PDEI - Master Project Context

> **Read this first in any new session.** It is the living index of the whole repository.
> Keep it updated as the project changes - it is the primary guide for reclaiming context.
>
> Last updated: 2026-08-28 · CI green · first stack boot in progress · 1,031 source files

---

## 0. Thirty-second orientation

**Pre-Dispute Evidence Intelligence (PDEI)** is an event-driven financial control platform that
continuously builds and verifies dispute-ready evidence for merchant transactions, detects
evidence gaps *before* disputes occur, and uses selective AI reasoning to assemble safe,
evidence-backed representment cases.

**The thesis:** the transaction is short-lived; the evidence that defends it is long-lived state
that must be continuously maintained.

**The architectural rule:** *deterministic systems establish financial truth; AI reasons only
about ambiguity.* Or: **AI proposes, policy disposes.**

Repo root: `C:\Users\Dell\dev\Laserpay` (moved off OneDrive 2026-08-28 - see §15). Git branch `master`.

---

## 1. Document hierarchy - what is authoritative

| Document | Role | Editable? |
|---|---|---|
| `planner/pre-dispute-evidence-intelligence-reference.md` | Original product brief (43 sections). The **why**. | Frozen - source of intent |
| `docs/PLATFORM-CONTRACT.md` | **NORMATIVE.** Every cross-service identifier: ports, topics, routes, enums, DB schema, readiness formula, AI contract, Temporal workflow, env vars. | Change deliberately; code follows it, never the reverse |
| `docs/SHARED-LIBRARY-API.md` | **NORMATIVE.** Exact class/method surface of the three shared Maven modules. | Same |
| `docs/architecture.md` | Narrative: the thesis, the funnel, three planes, per-technology justification, the 8 correctness properties. | Living |
| `docs/adr/` (10 ADRs) | Decisions with their forces and consequences. | Append; supersede rather than edit |
| `docs/event-catalog.md` | Every event with payload, producer, consumers, contradiction sources. | Living |
| `docs/testing-strategy.md` | What is tested at which level, plus the CI greps enforcing architectural rules. | Living |
| `docs/benchmark-plan.md` | Measure-don't-claim methodology + reporting template. | Living |
| `docs/demo-script.md` | 12-minute reproducible demo (seed 4281) + chaos matrix. | Living |
| `docs/glossary.md` | Domain vocabulary. | Living |
| `<module>/context.md` × 16 | Per-module deep context. | Living - update with the module |

**Rule that survives refactors:** if code and `PLATFORM-CONTRACT.md` disagree, **the code is wrong**.

---

## 2. Repository layout

```
Laserpay/
├── context.md                     ← this file
├── README.md
├── .gitignore .editorconfig
├── planner/                       original reference brief (1 file)
├── docs/                          19 files: contract, arch, ADRs, catalogs, strategy
├── schemas/                       35 JSON Schemas - events/ (32) + ai/ (3)
├── infra/                         22 files - docker-compose + all infra config
├── backend/                       655 files - Maven reactor `pdei-backend`, 12 modules
├── ai-reasoning-service/          58 files - Python FastAPI, package `pdei_ai`
├── frontend/                      223 source files - Next.js 15, `pdei-web`
├── scripts/                       13 dev scripts (.sh + .ps1 for Windows)
└── .github/workflows/ci.yml
```

Counts exclude `node_modules/`, `.next/`, `target/` - all gitignored build artifacts.

⚠️ **Keep the repo out of any synced folder.** It used to live under OneDrive, which tried to
sync `node_modules` (~32k files), `.next` (~2k) and would have added tens of thousands more from
Maven `target/`, firing bulk-delete prompts and slowing every build. It now lives at
`C:\Users\Dell\dev\Laserpay`.

---

## 3. Coordinates (from PLATFORM-CONTRACT §1–2)

| Item | Value |
|---|---|
| Maven groupId / Java base package | `com.laserpay.pdei` |
| Java / Spring Boot / Maven parent | 21 / 3.3.5 / `pdei-backend` |
| Python package / version | `pdei_ai` / 3.11+ (uv) |
| Node / frontend package | 20+ / `pdei-web` |
| Docker network / compose project | `pdei-net` / `pdei` |

### Service registry

| Service | Port | Kind | Package root |
|---|---|---|---|
| `api-gateway-service` | 8080 | Spring web + WS | `…pdei.api` |
| `ingestion-service` | 8081 | Spring web | `…pdei.ingestion` |
| `normalization-worker` | 8082 | Kafka worker | `…pdei.normalization` |
| `state-builder-worker` | 8083 | Kafka worker | `…pdei.statebuilder` |
| `readiness-worker` | 8084 | Kafka worker + scheduler | `…pdei.readiness` |
| `case-orchestrator-service` | 8085 | Temporal worker | `…pdei.orchestrator` |
| `document-processor-service` | 8086 | Kafka worker + web | `…pdei.docproc` |
| `audit-service` | 8087 | Kafka worker + web | `…pdei.audit` |
| `simulator-service` | 8088 | Spring web | `…pdei.simulator` |
| `ai-reasoning-service` | 8000 | Python FastAPI | `pdei_ai` |
| `frontend` | 3000 | Next.js | - |

### Infrastructure

Postgres 5432 (db/user/pass `pdei`) · Redis 6379 · Kafka `kafka:9092` internal / `29092` host ·
Kafka UI 8090 · MinIO 9000 API / 9001 console (`pdei-minio` / `pdei-minio-secret`) ·
Temporal 7233, UI 8233, namespace `pdei` · Prometheus 9090 · Grafana 3001 (admin/admin) ·
Loki 3100 · Tempo 3200 · OTel Collector 4317/4318.

Compose profiles: `core` (infra) · `app` (services) · `obs` (observability).

---

## 4. Backend - Maven reactor (`backend/`, 655 files, 73 test classes)

### 4.1 Shared library modules

**`platform-common`** - plain Java + Jackson, no Spring autoconfig, no JPA. Frozen API.
- `money.Money` - `(long amountMinor, String currency)`. Overflow-checked (`Math.addExact`),
  validates ISO-4217, throws `CurrencyMismatchException`, derives decimal exponent from
  `java.util.Currency` (never hardcodes /100). `toDisplayString()` is the *only* place a
  decimal point appears.
- `id.Ids` / `IdPrefix` / `SeededIdGenerator` - prefixed IDs (`MER- CUS- TX- PAY- ORD- SHP-
  DLV- REF- COM- EV- POL- DSP- CASE- INV- AUD- SIM-`); seedable for simulator determinism.
- `event.*` - `CanonicalEvent` (+Builder), `EventType`, `AggregateType`, `EventSource`,
  `RawEventEnvelope`, `DeadLetterEnvelope`, `AuditEvent`, `ActorType`.
- `domain.*` - the 14 shared enums (see §6).
- `kafka.Topics` / `ConsumerGroups` / `EventHeaders`.
- `hash.Hashes` (sha256, canonical-JSON hash, `chain()`), `json.Json` (the one ObjectMapper),
  `error.PdeiException` sealed hierarchy, `time.Clocks`, `metrics.MetricNames`.

**`platform-persistence`** - JPA entities, Spring Data repositories, Flyway.
- Migrations `V1__baseline` → `V10__fts` create **28 tables** (§7).
- `MoneyEmbeddable` maps `Money` to two columns.
- `ProcessedEventRepository.markProcessed(eventId, consumerGroup)` - native
  `INSERT … ON CONFLICT DO NOTHING`, returns `boolean firstTime`. **The canonical idempotency
  primitive.**
- `PersistenceAutoConfiguration` registered via `AutoConfiguration.imports`.

**`evidence-core`** - the deterministic domain engine, the intellectual heart.
Packages: `ai/ audit/ config/ dispute/ evidence/ model/ policy/ readiness/ safety/ search/
spi/ spi/jdbc/ spi/kafka/ storage/ timeline/ util/`
- `readiness.ReadinessEngine` - the scoring formula (§8). Pure; testable without a DB via an
  injected data provider port.
- `readiness.GapDetector` / `ContradictionDetector`.
- `policy.PolicyEngine` - reason-code → requirement matrix, action permission, thresholds.
- `safety.AiResultValidator` - the 7 rejection rules (§9). `safety.SafetyGate`.
- `ai.AiReasoningClient` (interface) / `HttpAiReasoningClient` / `AdmissionController`.
- `evidence.EvidenceService` / `EvidenceIntegrityService` / `EvidenceGraphService` /
  `EvidenceLineageService`.
- `storage.ObjectStore` → `MinioObjectStore`. `dispute.CaseAssemblyService`.
  `audit.AuditRecorder` (hash-chained). `search.EvidenceSearchService` (Postgres FTS).
- `model.*` - ~24 immutable records shared across services: `ReadinessSnapshot`, `ReadinessGap`,
  `EvidenceView`, `EvidenceGraph`, `TimelineEntry`, `InvestigationContext`,
  `InvestigationResult`, `SafetyVerdict`, `CaseXRay`, `PackageManifest`, `FunnelMetrics`, …

### 4.2 Deployable services

| Service | Consumes | Produces | Key classes |
|---|---|---|---|
| `ingestion-service` | HTTP | `pdei.raw.events.v1` | `IngestionController`, `WebhookController`, `SchemaRegistry`, `IdempotencyService` |
| `normalization-worker` | `raw` | `canonical`, `dlq` | `SourceAdapter` per system, `SourceAdapterRegistry`, `EventUpcaster` |
| `state-builder-worker` | `canonical` | `evidence`, `dispute` | per-aggregate handlers + `StateBuilderDispatcher`; derives evidence from lifecycle facts |
| `readiness-worker` | `evidence`, `canonical` | `readiness` | debounced recompute (Redis lock), `ExpirySweepJob`, `AtRiskScanner` |
| `case-orchestrator-service` | `dispute` | `case` | `DisputeCaseWorkflow(Impl)`, `CaseActivities`, `DisputeEventListener`, `CaseSignalService` |
| `document-processor-service` | `evidence` | evidence text | Tika/PDFBox/EML extractors, `ExtractorRegistry` |
| `audit-service` | `audit` + all domain topics | audit chain | `ChainVerifier`, `AuditController` |
| `simulator-service` | HTTP | `raw` | `WorldGenerator`, `ScenarioLibrary`, `ChaosEngine`, `ReplayService`, `EventEmitter` |
| `api-gateway-service` | `readiness`,`case`,`evidence`,`dispute` | HTTP/WS/SSE | 12 controllers + `AiToolsController` + `ControlTowerWebSocketHandler` |

**Controller classes (20):** `AdmissionController`, `AiToolsController`, `AuditController` ×2,
`CaseController`, `DisputeController`, `DocProcController`, `EventStreamController`,
`EvidenceController`, `GapController`, `HealthController`, `IngestionController`,
`InvestigationController`, `MerchantController`, `MetricsFunnelController`,
`OrchestratorController`, `PolicyController`, `SimulationController`, `TransactionController`,
`WebhookController`.

**Base paths in use:** `/api/v1/{merchants,transactions,evidence,disputes,cases,investigations,
gaps,audit,metrics,health,stream,ai-tools}` · `/ingest/v1` · `/docproc/v1` · `/audit/v1` ·
`/orchestrator/v1` · `/sim/v1`.

---

## 5. Kafka topology

| Topic | Parts | Producers | Consumers |
|---|---|---|---|
| `pdei.raw.events.v1` | 12 | ingestion, simulator | normalization |
| `pdei.canonical.events.v1` | 12 | normalization | state-builder, audit, docproc |
| `pdei.evidence.events.v1` | 12 | state-builder, docproc | readiness, audit |
| `pdei.readiness.events.v1` | 12 | readiness | api-gateway, audit |
| `pdei.dispute.events.v1` | 12 | state-builder, ingestion | orchestrator, audit |
| `pdei.case.events.v1` | 12 | orchestrator | api-gateway, audit |
| `pdei.audit.events.v1` | 6 | all | audit |
| `pdei.dlq.v1` | 6 | all consumers | manual replay |

**Partition key (mandatory, everywhere):** `merchantId + ":" + aggregateId`.
**Consumer groups:** `pdei-<service-name>`.
**Every consumer is idempotent** - Redis `SETNX` on `pdei:idem:{eventId}` (7d TTL) plus
`processed_events` `ON CONFLICT DO NOTHING`.

Ordering guarantee: per-aggregate only. Cross-aggregate events *will* interleave - a
`ShipmentDelivered` can be processed before its `OrderCreated`. Handlers tolerate this;
readiness converges once both land.

---

## 6. Shared enums (spell identically in Java / Python / TypeScript)

```
EvidenceType     PAYMENT_PROOF INVOICE ORDER_RECORD SHIPPING_RECORD DELIVERY_PROOF
                 REFUND_RECEIPT CUSTOMER_COMMUNICATION MERCHANT_POLICY TERMS_OF_SERVICE
                 AVS_CVV_RESULT DEVICE_FINGERPRINT PRIOR_TRANSACTION_HISTORY SIGNED_CONTRACT
EvidenceStatus   PENDING ACTIVE EXPIRING EXPIRED INVALIDATED SUPERSEDED
EvidenceSource   PSP_ADAPTER ORDER_SYSTEM LOGISTICS CRM DOCUMENT_UPLOAD MERCHANT_PORTAL
                 SIMULATOR INTERNAL_DERIVED
DisputeReasonCode GOODS_NOT_RECEIVED SERVICE_NOT_RENDERED PRODUCT_NOT_AS_DESCRIBED
                 DUPLICATE_PROCESSING CREDIT_NOT_PROCESSED SUBSCRIPTION_CANCELLED
                 FRAUDULENT_TRANSACTION UNRECOGNIZED_TRANSACTION INCORRECT_AMOUNT
                 PAID_BY_OTHER_MEANS
DisputeStatus    OPEN EVIDENCE_GATHERING UNDER_INVESTIGATION AWAITING_HUMAN_REVIEW
                 REPRESENTMENT_PREPARED SUBMITTED WON LOST EXPIRED WITHDRAWN
CaseStatus       CREATED ASSEMBLING INVESTIGATING AWAITING_EVIDENCE AWAITING_APPROVAL
                 PREPARED SUBMITTED CLOSED FAILED
ReadinessBand    READY(>=90) NEARLY_READY(75-89) AT_RISK(50-74) NOT_READY(<50)
RequirementStrength MANDATORY(3) RECOMMENDED(2) OPTIONAL(1) PROHIBITED(0)
GapType          MISSING EXPIRED EXPIRING_SOON CONTRADICTORY UNVERIFIABLE_PROVENANCE
                 LOW_QUALITY VERSION_CONFLICT
GapSeverity      LOW MEDIUM HIGH CRITICAL
InvestigationClassification DEFENDABLE WEAK INDEFENSIBLE INSUFFICIENT_EVIDENCE AMBIGUOUS
RecommendedAction PREPARE_REPRESENTMENT GATHER_MORE_EVIDENCE ACCEPT_LIABILITY
                 ESCALATE_TO_HUMAN REQUEST_POLICY_REVIEW
SafetyDecision   ALLOW ALLOW_WITH_REVIEW DENY
ChaosType        DUPLICATE_EVENT DELAYED_EVENT OUT_OF_ORDER_EVENT DROP_EVENT DELETE_EVIDENCE
                 CORRUPT_EVIDENCE_HASH EXPIRE_EVIDENCE CONFLICTING_EVIDENCE KILL_WORKER
                 RESTART_CONSUMER REPLAY_EVENTS INJECT_DISPUTE SLOW_CONSUMER
```

---

## 7. Database - schema `pdei`, 28 tables

```
V1  merchants, customers, processed_events
V2  transactions, payments, orders, order_lines, refunds, shipments, deliveries, communications
V3  evidence, evidence_versions, evidence_relationships
V4  policies, policy_versions, evidence_requirements
V5  disputes, dispute_cases, case_evidence
V6  readiness_snapshots, readiness_gaps
V7  investigations, investigation_findings, ai_admission_log
V8  audit_events                      (hash-chained: previous_hash + hash)
V9  simulation_runs, chaos_injections
V10 tsvector columns + GIN indexes + maintenance trigger (FTS)
```

**Money:** every monetary column is `amount_minor BIGINT` + `currency CHAR(3)`. No
FLOAT/DOUBLE/NUMERIC for money anywhere. **Time:** all `TIMESTAMPTZ`, UTC; Java `Instant`;
never `LocalDateTime`. **Keys:** `VARCHAR(64)` prefixed IDs.

---

## 8. Readiness scoring (deterministic - never model-derived)

```
base = 100 * (Σ weight(satisfied mandatory) + 0.5·Σ weight(satisfied recommended))
           / (Σ weight(all mandatory)       + 0.5·Σ weight(all recommended))
penalties:  −15 per CONTRADICTORY gap
            −10 per EXPIRED mandatory
            − 5 per EXPIRING_SOON mandatory (expiry within 7 days)
            −20 if any UNVERIFIABLE_PROVENANCE on mandatory
score = clamp(round_half_up(base − penalties), 0, 100)   → ReadinessBand
```

Weights MANDATORY=3 / RECOMMENDED=2 / OPTIONAL=1 / PROHIBITED=0. With no `reasonCode`, use the
merchant's baseline profile (union of MANDATORY across their top reason codes).
Recompute triggers: any EVIDENCE event, linked-entity state change, policy version change,
nightly expiry sweep.

---

## 9. AI subsystem

### Boundary
All model code lives in `ai-reasoning-service` (Python). **No Gemini SDK, no prompt text, and
no model client exists anywhere under `backend/`** - enforced by a CI grep. The AI service has
**no database credentials**; it sees only the curated `InvestigationContext` plus ten read-only
`GET` tools under `/api/v1/ai-tools/*` (token: header `X-PDEI-Service-Token`). The tool
executor structurally refuses non-GET requests and unknown tool names.

### FastAPI routes (prefix `/v1`)
`POST /investigate` · `POST /investigate/stream` (SSE) · `POST /admission/score` ·
`POST /narrative` · `GET /tools` · `GET /providers` · plus `/health`, `/ready`, `/metrics`.

### Reasoners (`pdei_ai/reasoners/`)
`base.py` (Protocol) · `gemini.py` · `mock.py` · `null.py` · `registry.py`.
Selected by `PDEI_AI_PROVIDER=gemini|mock|null`. **Dev default `mock`** - deterministic, seeded
from `investigationId`, no wall clock. Fallback chain `gemini → mock`. The whole stack works
with no API key.

### The 7 validation rules (`AiResultValidator`) - reject when ANY holds
1. an `evidenceId` cited does not exist in Postgres;
2. evidence not linked to this case's transaction;
3. `recommendedAction` not policy-permitted;
4. `confidence < policy.autoPrepareMinConfidence` while action is PREPARE_REPRESENTMENT;
5. `contradictions > policy.maxContradictions` while action is PREPARE_REPRESENTMENT;
6. a prohibited evidence type appears in `supportingEvidence`;
7. `classification == DEFENDABLE` while any MANDATORY requirement is unsatisfied.

Rejection → `SafetyDecision.DENY` → `AWAITING_HUMAN_REVIEW`, always audited.
Metric `pdei_ai_unsupported_claims_total`. **No confidence value unlocks rule 7.**

### Admission control
```
priority = 0.40·financialImpact + 0.25·deadlineUrgency
         + 0.20·ambiguityScore  + 0.15·(1 − deterministicConfidence)
admit if priority ≥ 55 AND token bucket allows AND deterministic path unresolved
```
Deterministic short-circuits that bypass the model entirely: all mandatory satisfied + zero
contradictions → auto-prepare; zero evidence → accept liability (to human); past deadline →
escalate. Recorded as `aiInvoked:false` + `bypassReason` - the source of the AI-reduction metric.

---

## 10. Temporal

Namespace `pdei` · task queue `pdei-dispute-cases` · workflow id `case-{caseId}`.

`DisputeCaseWorkflow` - 12 steps: openCase → gatherEvidence → detectGaps →
awaitMissingEvidence (timer + signal, max 7d) → runAdmissionControl → investigate →
validateAndGate → awaitHumanApproval → prepareRepresentmentPackage → submitRepresentment →
followUp → closeCase.

Signals: `evidenceArrived`, `humanDecision`, `disputeUpdated`, `cancelCase`.
Queries: `getCaseState`, `getProgress`.
Retry: initial 1s, backoff 2.0, max 60s, 10 attempts; non-retryable `PolicyViolationException`,
`ValidationException`. Workflow code is deterministic - all side effects via activities.

---

## 11. Frontend (`frontend/`, 223 source files)

Next.js 15 App Router · React 19 · TS strict · Tailwind · shadcn/ui · TanStack Query v5 ·
Zustand · Recharts.

**14 routes:** `/` (→ control-tower) · `/control-tower` · `/transactions` ·
`/transactions/[transactionId]` · `/evidence` · `/evidence/[evidenceId]` · `/disputes` ·
`/disputes/[disputeId]` · `/cases` · `/cases/[caseId]` (**Case X-Ray**) · `/policies` ·
`/simulation` · `/observability` · `/settings`.

Structure: `src/app` (101) · `src/lib` (47) · `src/components` (54) · `src/mocks` (6).

Layering rule: **all** API access goes through `src/lib/api/client.ts` + `endpoints/*.ts`.
One client, one type set. `useControlTowerSocket` handles WS with reconnect/backoff;
`useInvalidateOnWsEvent` maps frame types to query keys.

`NEXT_PUBLIC_USE_MOCKS=true` renders every screen from deterministic fixtures with the backend
down. **Verified 2026-08-27: `npx tsc --noEmit` reports 0 errors**, and `next build` produced
`.next/server`, `static`, `types`. The frontend is the one part of the stack proven to compile.

**Case X-Ray tabs:** Overview (workflow stepper) · Timeline · Evidence · Graph · AI Reasoning
(claims rendered next to cited evidence IDs; bypass reason shown when AI was skipped) ·
Safety Gate (each rule, pass/fail) · Package (manifest + hashes).

---

## 12. Observability

Metric prefix `pdei_`. Key metrics: `pdei_events_{ingested,processed,duplicate}_total`,
`pdei_event_processing_latency_seconds`, `pdei_kafka_consumer_lag`,
`pdei_readiness_computation_seconds`, `pdei_case_assembly_seconds`,
`pdei_ai_{requests,admission}_total`, `pdei_ai_latency_seconds`,
`pdei_ai_unsupported_claims_total`, `pdei_policy_gate_total`, `pdei_workflow_failures_total`,
`pdei_chaos_injections_total`.

OTLP → collector `:4318` → Prometheus + Loki + Tempo. Trace context propagates through Kafka
headers (`traceparent`). JSON logs carry `traceId`, `spanId`, `merchantId`, `correlationId`.

Grafana dashboards: `pdei-event-pipeline`, `pdei-evidence-readiness`, `pdei-ai-usage`,
`pdei-workflow-health`.

---

## 13. The 8 correctness properties (the real spec)

1. **Idempotency** - N deliveries ≡ 1. Redis SETNX + `processed_events`.
2. **Out-of-order tolerance** - projections carry `last_event_occurred_at`; older events ignored.
3. **Replayability** - state is a fold over the log; replay reproduces identical scores.
4. **Evidence immutability** - new version supersedes, never overwrites.
5. **Integrity** - SHA-256 at write; re-hash detects corruption → `INVALIDATED` + audit.
6. **Deterministic readiness** - same inputs → same integer, always.
7. **AI groundedness** - every claim cited; unresolvable citations reject the result.
8. **Workflow durability** - killing the orchestrator loses nothing.

Each maps to a chaos injection that proves it (`docs/testing-strategy.md` §7).

---

## 14. Running it

```bash
./scripts/up.sh          # or scripts/up.ps1 on Windows
./scripts/smoke-test.sh  # health table for every service
./scripts/seed-demo.sh   # drives the simulator for the demo
./scripts/reset.sh       # wipe volumes
```

Compose profiles: `--profile core` (infra only) · `app` · `obs`.
Demo walkthrough: `docs/demo-script.md` (seed 4281, ~12 min).

**Backend build verified 2026-08-28.** `mvn test` → **BUILD SUCCESS, all 12 modules,
505 tests, 0 failures.** Toolchain used: Temurin JDK 21.0.12 (user-scope install at
`%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21.0.12.101-hotspot`) + Maven 3.9.9.

⚠️ Maven is **not** installed system-wide and `JAVA_HOME` is **not** set globally - set both, or
run Maven from a copy you unzip yourself. Docker Desktop must be running for the integration
suite (Testcontainers) and for `docker compose`.

---

## 15. Build history & known state

The baseline was generated by a 22-agent workflow: 3 foundation → 9 services → 2 frontend page
agents → 3 adversarial audits → 4 repair agents → 1 inventory.

**The audit found 31 issues; the 15 CRITICAL/HIGH ones were repaired** (the 16 MEDIUM/LOW were
logged, not fixed). All were cross-service contract drift, since no agent could see another's
code. Verified after repair: `PageResponse<T>` is `{content,page,size,totalElements,totalPages}`
on both sides; `missingEvidence` is `EvidenceType` in all three languages; the simulator's raw
-topic partition key is `merchantId + ":" + aggregateId`; frontend `tsc --noEmit` is clean.

Representative classes of defect, worth re-checking after any large change:

- **Response-shape drift** - frontend `PageResponse<T>` was `{items,total}` vs gateway
  `{content,totalElements,totalPages}`; `MerchantSummary` nested vs flat; `TransactionDetail`
  flat vs nested; `policies.list()` paginated vs bare list.
- **Tri-language type divergence** - `GapRef.evidenceId` / `ContradictionRef.left|right` carried
  a Pydantic `^EV-` regex while Java put entity IDs there; `missingEvidence` had three different
  types; `ShortCircuit` union incomplete in TS.
- **Silent data loss** - every `GAP_DETECTED` WS frame discarded (parser required `gapId`, the
  gateway never sent it).
- **Contract violations** - simulator `EventEmitter` partition key not `merchantId:aggregateId`;
  `pdei_events_processed_total` registered with two different tag-key sets.
- **Missing CORS** on `simulator-service` (browser calls :8088 cross-origin).

### First real build - 2026-08-28

CI failed on first push (5 red / 2 green). Root causes found by compiling locally; six defects,
all mechanical, none architectural:

| # | Defect | Blast radius |
|---|---|---|
| 1 | `--` inside an XML comment in `case-orchestrator-service/pom.xml` (6 banner comments) - non-parseable POM | **entire reactor**; nothing compiled |
| 2 | Unescaped backslashes, 7× in `Buckets.java`, `Text.java`, `BucketsTest.java` | `evidence-core` |
| 3 | `CoreErrors.upstream(msg)` called a constructor that needs `(upstream, msg)`; 9 call sites updated to name MinIO | `evidence-core` |
| 4 | `ExponentialBackOffWithMaxRetries` imported from `org.springframework.util.backoff`; it lives in `org.springframework.kafka.support` | 3 Kafka configs |
| 5 | `io.temporal:temporal-spring-boot-starter-alpha:1.25.1` never existed - `-alpha` was retired at 1.23.2, artifact renamed | orchestrator |
| 6 | `RawEventValidator` reported `required` violations against the containing object (`body`) instead of the missing field (`body.createdAt`) | ingestion API usability |

Two of these are worth remembering. **#5**: the parent POM already had the correct non-alpha
artifact in `dependencyManagement` while the child used `-alpha` - two agents, two answers,
invisible until resolution ran. **#6** was a genuine API defect, not a typo: networknt reports a
`required` violation against the *parent* object with the absent property in `getProperty()`,
so integrators would have been told "something is wrong with `body`". Fixed in the validator
(also covers `additionalProperties`), not by relaxing the test.

Also fixed: `ruff format` on 22 of 53 Python files (the AI-service CI failure), and
`Text.java` used `"\s+"`, which compiles - `\s` is a legal Java escape meaning a literal
space - but silently failed to collapse tabs and newlines. A latent bug no test would catch.

Infra CI failed on **shellcheck** alone (it exits non-zero on any finding): `C_DIM`/`C_BOLD`
flagged unused in `lib.sh` (they are used by sourcing scripts - fixed with `export`, which is
what the code meant), an `A && B || C` in `reset.sh` (SC2015 - rewritten as if/else), and an
unused `read` field in `smoke-test.sh`. hadolint already passed: every finding is warning/info,
below CI's `error` threshold. Compose config validates on all three profiles.

### The integration suite - was a false green, now genuinely passes (9/9)

`AbstractPostgresIntegrationTest.dockerAvailable()` gates the Testcontainers tests via
`@EnabledIf`. When Docker is unreachable they **skip silently**, so "Backend (integration)"
reports success having executed nothing:

```
EvidencePersistenceIntegrationTest       Tests run: 4, Skipped: 4   (0.002s)
ProcessedEventRepositoryIntegrationTest  Tests run: 5, Skipped: 5   (0.001s)
```

Fixed by making the guard return `true` when `CI=true` (GitHub sets it), so a runner without
Docker fails loudly instead of going green. **Local dev still skips gracefully.**

Once they actually ran, **two genuine bugs** appeared - both environment-independent, and both
were almost certainly the real CI failures:

**1. Singleton-container anti-pattern (the serious one).**
`AbstractPostgresIntegrationTest` was annotated `@Testcontainers` with a static `@Container`
field, and its javadoc claimed the container was "shared by every subclass". It was not: the
JUnit Testcontainers extension **stops an annotated static container at the end of every test
class**. The second subclass then started a fresh container on a new random port while Spring
reused its *cached* application context, still pointing at the dead one. Symptom: the first
class passed, then every later test died on a 30s Hikari timeout
(`Connection is not available, request timed out after 30001ms`).
Fixed with the real singleton pattern - no `@Container`, no `@Testcontainers`, started lazily
and idempotently inside `@DynamicPropertySource`, never stopped (Ryuk/JVM exit reaps it). Lazy
start also keeps `dockerAvailable()` meaningful on a Docker-less box.

**2. `migrationsApplied` counted a non-migration row.**
It asserted `count(*) FROM flyway_schema_history WHERE success == 10` and got **11**. Flyway 10
records a `<< Flyway Schema Creation >>` marker (NULL version, type `SCHEMA`) when it creates
the schema. All ten migrations had applied correctly; the assertion was wrong. Now filtered
with `AND version IS NOT NULL`.

Independently verified that all ten migrations apply cleanly to a real PostgreSQL 16 by
replaying `V1..V10` through `psql` - the schema itself was never at fault.

**Local-only caveat (does not apply to CI).** Testcontainers could not reach Docker on this
machine at all. Root cause, finally readable only via a TCP daemon:
`client version 1.32 is too old. Minimum supported API version is 1.40`. docker-java falls back
to API **v1.32**, and Docker 29 dropped everything below 1.40 - the client is too *old*, not too
new (an earlier note in this file guessed the opposite; that was wrong). Bumping Testcontainers
to 1.21.3 does not help and was reverted (still 1.20.3).
Workaround used for local verification only: `-DargLine=-Dapi.version=1.44`.
This is **not** applied to CI, because it is an artifact of driving a daemon over `DOCKER_HOST`
(tcp) - the `EnvironmentAndSystemPropertyClientProviderStrategy` does not negotiate a version,
whereas GitHub runners use the unix-socket strategy, which does.

**How to run the integration suite on a machine where Docker Desktop blocks Testcontainers:**

```bash
docker network create pdei-test-net
docker run -d --privileged --name pdei-dind --network pdei-test-net \
  -e DOCKER_TLS_CERTDIR="" docker:dind --host=tcp://0.0.0.0:2375
docker run --rm --network pdei-test-net -e DOCKER_HOST=tcp://pdei-dind:2375 \
  -e CI=true -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v "<repo>:/repo" -v "$HOME/.m2:/root/.m2" -w /repo/backend \
  maven:3.9-eclipse-temurin-21 \
  mvn -B verify -DskipUTs=true -DargLine=-Dapi.version=1.44
```

### Open gaps / TODOs
- [x] ~~Backend never compiled~~ - **compiles clean; 505 unit tests pass** (2026-08-28).
- [x] ~~Integration tests never actually executed~~ - **9/9 pass** against real PostgreSQL 16.
- [x] ~~`skipUTs`/`skipITs` undefined; failsafe never bound~~ - **fixed**. Both properties now
      exist; surefire excludes `*IntegrationTest.java`/`*IT.java` and honours `skipUTs`; failsafe
      includes exactly those, honours `skipITs`, and is bound to `integration-test`+`verify` for
      every module. The CI matrix's two suites are now genuinely different.
- [x] ~~Python suite never executed~~ - **89 pytest tests pass**, plus ruff, ruff-format and mypy
      (42 files) clean. `uv.lock` committed (74 packages), which also fixes `setup-uv`'s cache
      glob and activates the `uv sync --frozen` reproducible path.
- [x] ~~`docker compose up` never run end-to-end~~ - **the stack boots; 20/20 smoke checks
      pass** (2026-08-28). Six boot defects found and fixed; see “First `docker compose up`”.
- [ ] **The event pipeline does not work yet.** 49% of raw events dead-letter, zero evidence
      reaches the database, and the funnel is empty past stage one. Five root causes, A–E,
      written up in “First `docker compose up`” below. A and B are the blockers.
- [ ] 16 MEDIUM/LOW audit findings logged but not fixed - re-run an audit to recover the list.
- [ ] `InvestigationEntity.missingEvidence` (jsonb) has **no readers or writers** anywhere -
      dead field, or a persistence path that was never wired. Decide which.
- [ ] `isEvidenceType()` in `frontend/src/app/cases/[caseId]/_components/AiReasoningTab.tsx`
      is a tautological guard (else-branch narrows to `never`). Harmless, but dead.
- [ ] `pdei_events_processed_total` tag *values* differ in style across `DisputeEventListener`
      call sites (`failure` vs `HANDLED`/`IGNORED`/`FAILED`). Keys match, so Micrometer is
      safe, but the label cardinality is untidy.
- [ ] `SimulatedNetworkSubmitter` is a named seam - real PSP submission out of scope.
- [ ] No OCR (deliberate - reference doc §25).
- [ ] Benchmarks unrun; `benchmarks/results/` does not exist yet.

### First `docker compose up` - 2026-08-28

**The stack boots.** `./scripts/up.sh core app` brings up all 20 components and
`./scripts/smoke-test.sh --core --app` reports **20/20 UP with zero restarts**. All 11 images
build from source in ~9 minutes (not the 30–60 estimated); core alone is healthy in ~45 s.

Verified against the running stack rather than the config: 8 topics with contract §4 partition
counts and `pdei.readiness.events.v1` carrying `cleanup.policy=compact,delete`; both MinIO
buckets versioned; Postgres schema `pdei` with roles + extensions, TimeZone UTC; Temporal
namespace `pdei` at 72 h retention; Flyway applied all 10 migrations → 28 tables + history.

#### Six defects found by booting - all fixed (`d5f1915`, `7b9d6bf`)

| # | Defect | Why nothing caught it earlier |
|---|---|---|
| 1 | `temporalio/admin-tools:1.25.1` does not exist - that repo published no bare semver tags before 1.26. Compose aborts **all** parallel pulls on one resolution failure, so the symptom was "nothing starts". Pinned `1.25.1-tctl-1.18.1-cli-1.1.1`. | Image tags are only resolved by a real pull. |
| 2 | `smoke-test.sh` reported Kafka DOWN on a healthy broker: `check_exec` passes `/opt/kafka/bin/...` to a native `docker.exe` and Git Bash rewrites it to `C:/Program Files/Git/opt/...`. Fixed with `MSYS_NO_PATHCONV=1` + `MSYS2_ARG_CONV_EXCL='*'`. | Windows-only; CI runs on Linux. |
| 3 | `api-gateway-service` crash-looped: `NoClassDefFoundError GenericObjectPoolConfig`. `application.yml` enables `lettuce.pool` and `spring-boot-starter-data-redis` declares `commons-pool2` **optional**. Added the dependency. | The class is only touched when pooling is switched on at runtime. |
| 4 | `audit-service` + `readiness-worker` crash-looped: *"JsonDeserializer must be configured with property setters, or via configuration properties; not both"*. Each `KafkaConfig` builds the deserializer programmatically (to inject the shared `Json.mapper()`) **and** the YAML set `spring.json.trusted.packages`. Removed the YAML half. | Needs a real Kafka consumer to start. |
| 5 | `frontend` ran `next start` against `output: 'standalone'`; Next.js 15 rejects that combination out loud but still served `/api/health`, so the healthcheck stayed green. Now serves `.next/standalone` + `.next/static` + `public/` via `node server.js`. | A green healthcheck hid it. |
| 6 | The documented claim that `--profile app` alone works via `depends_on` auto-enable is **false** on Compose v5.1.4 - that, `COMPOSE_PROFILES=app`, and naming a service all fail project validation. Corrected in the compose file and `infra/context.md`. | The scripts always pass both profiles. |

#### Then the pipeline was exercised, and it does not work yet

`./scripts/seed-demo.sh --small` plus the three curated scenarios emitted **5672** raw events.
Downstream, the funnel is dead after stage one:

```
pdei.raw.events.v1        5672     simulator → Kafka: correct
pdei.canonical.events.v1  2784     normalization: only half got through
pdei.dlq.v1               2756     ← 49% of all events dead-lettered
pdei.evidence.events.v1      0     ← no evidence exists anywhere
pdei.dispute.events.v1       4     (24 were generated)
pdei.case.events.v1          0
Postgres: transactions 21 (of 324) · evidence 0 · disputes 4 · dispute_cases 0 · investigations 0
GET /api/v1/metrics/funnel: events 8336 → candidates 0 → ambiguous 0 → aiInvestigated 0
```

⚠️ **Measuring topic depth:** `kafka.tools.GetOffsetShell` moved package in Kafka 3.x and now
prints nothing while exiting 0 - it reported every topic as empty and nearly sent this
investigation the wrong way. Use `kafka-get-offsets.sh --topic-partitions 'pdei.*'`.

Five distinct root causes, none yet fixed. **A and B are the blockers**; C, D and E are
independent and smaller.

**A. Raw-event vocabulary drift - 2756 DLQ, all `UnmappableEventException`.**
`simulator/world/SourceVocabulary.java` calls itself "the mapping table normalization-worker
must implement". It is not the table the adapters implement:

| Simulator emits | Adapter | Adapter's vocabulary | DLQ |
|---|---|---|---|
| `merchant-portal` / `document.uploaded` | `MerchantPortalAdapter` | no `document.*` mapping at all | 2004 |
| `crm` / `message.sent` | `CrmAdapter` | `email.sent`, `message.outbound`, `sms.sent`, … | 362 |
| `crm` / `message.received` | `CrmAdapter` | `email.received`, `message.inbound`, … | 123 |

`psp-adapter`, `order-system` and `logistics` agree, which is why 2784 events did normalize.
`document.uploaded` is the only evidence-bearing source event, so losing it means the platform
holds **zero evidence** - the product is a no-op. Root cause: PLATFORM-CONTRACT §4 says raw
events are "source-shaped" but never pins the strings, so two authors chose two vocabularies.
**The contract needs a normative source-system → sourceEventType → EventType table.**

**B. `JdbcEvidenceRepository` is written against a schema that does not exist.**
`evidence-core/spi/jdbc/JdbcEvidenceRepository.java` - 31 failures in state-builder,
`BadSqlGrammarException … column "id" does not exist`. Its `COLUMNS` list disagrees with
`V3__evidence.sql` in three different ways:

| Kind | Query assumes | Flyway has |
|---|---|---|
| rename | `evidence.id` | `evidence_id` |
| rename | `evidence_versions.id` / `.version` | `evidence_version_id` / `version_number` |
| rename | `evidence_relationships.id` / `.relation` | `relationship_id` / `relationship_type` |
| **semantic collision** | `evidence.version`, meaning the version number | `current_version`; `version` exists but is the JPA optimistic-lock counter |
| **absent** | `parent_evidence_id`, `quality_score`, `provenance_verified` | no such columns |

The renames are mechanical. The three absent columns are not dead code - each is load-bearing
and required by the contract:

- `parentEvidenceId` drives `EvidenceLineageService`'s version-chain walk and
  `EvidenceGraphService`'s `SUPERSEDES` edges - correctness property #4. (`superseded_by` is the
  *forward* pointer and its complement, not a substitute.)
- `provenanceVerified` drives `GapType.UNVERIFIABLE_PROVENANCE`, which carries the **−20**
  penalty in the readiness formula (§7).
- `qualityScore` drives `GapType.LOW_QUALITY`; `POST /evidence` already validates it 0.0–1.0.

So the **migrations are incomplete relative to the contract** and the fix is a new migration,
not deletion. The file's javadoc says "if the Flyway migrations name them differently, this is
the only file that needs changing" - true for the renames, wrong for these three.

**C. Out-of-order tolerance breaks on the transaction FK.** `ShipmentCreated` arriving before
`OrderCreated` makes state-builder insert a stub order
(`metadata: {"pdeiStub":true,"pdeiStubReason":"implied by ShipmentCreated …"}`), but the stub
points at a `transaction_id` that does not exist yet, so `fk_orders_transaction` rejects it.
The stub strategy covers the order-level gap and not the transaction-level one. This is
correctness property #2 failing against real interleaving, and it is why only **21 of 324**
transactions persisted.

**D. `disputeRate` unit mismatch - silently zeroes every dispute.** Contract §8.5 names the
field but never gives a unit, and three readings exist:

- `scripts/seed-demo.sh` sends `disputeRate: 0.02` (a fraction);
- `frontend/.../RunLauncher.tsx` sends `disputeRate: percent * 0.01` (a fraction);
- `CreateRunRequestDto` declares `Integer disputeRate` in **basis points**.

Jackson truncates `0.02` → `0`, so the world run reported `disputeRateBps: 0` and created **0
disputes**. `RunProgressPanel.tsx` then renders "0 bps dispute rate" back at the user who asked
for 3%. The three curated scenarios set `disputeRateBps: 10000` internally and *do* produce
disputes, which is why any exist at all. The backend and its own `context.md` are self-consistent
on integer basis points (same reproducibility argument as money); **both callers are wrong**, and
the contract must state the unit with `disputeRateBps` as the canonical spelling.

**E. `GET /sim/v1/runs/{runId}` is wrapped; the list endpoint is not.** The detail route returns
`{"run":{…},"scenario":{…}}` while `GET /runs` returns bare run objects. `seed-demo.sh` reads
`status` at the top level, so it never observes progress or completion: every run prints
`events=?` and ends with "did not report completion within 300s" even when it finished in 15 s.
Contract §8.5 does not specify the envelope - the same unspecified-shape failure as D.

#### Still unexplained (downstream of the above, probably)

24 disputes were generated but only 4 reached `pdei.dispute.events.v1` and Postgres, and
`dispute_cases` is 0 - no Temporal workflow ever opened. Likely a consequence of the missing
transaction rows (C) and missing evidence (A+B); re-check after those are fixed rather than
treating it as a sixth root cause.

#### What is not a defect

`Failed to export spans … otel-collector: Name or service not known` (27× on the gateway) is
expected when `obs` is not running. WARN, non-fatal. `up.sh core app obs` silences it.

### Repo relocated + line-ending policy (2026-08-28)

The repo now lives at **`C:\Users\Dell\dev\Laserpay`** (was under OneDrive, which was syncing
~1 GB of `node_modules`/`.next`/`target` and firing bulk-delete prompts). Moved by fresh clone.
That clone exposed two latent bugs:

**1. No `.gitattributes` + `core.autocrlf=true` → CRLF everywhere → broken stack.**
`scripts/lib.sh:load_env_file` reads `infra/.env` with `IFS= read -r line`, keeping the trailing
CR, and exports `PDEI_KAFKA_HOST_PORT=29092<CR>`. Exported variables take precedence over
compose's own `.env` parsing, so `docker compose config` failed with `invalid hostPort: 29092`
- the CR invisible in the message. It looked intermittent because the reported port varied with
which export was validated first, and plain `docker compose` (no shell exports) always worked.
Fixed by adding `.gitattributes` (`* text=auto eol=lf`; `.bat`/`.cmd`/`.ps1` stay CRLF; binaries
excluded) then renormalising the tree. **CRLF also breaks `.sh` outright** (`$'\r': command not
found`), and every service here runs in a Linux container reading bind-mounted files, so LF is
the only correct checkout format for this repo.

**2. `frontend/.env.local.example` was never committed.** `.gitignore`'s `.env.*` swallowed it
and the negations only named `.env.example` / `infra/.env.example`. A fresh clone therefore had
no record of `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_WS_URL`, `NEXT_PUBLIC_USE_MOCKS`,
`NEXT_PUBLIC_SIM_BASE_URL`. Recovered from the old folder; rule is now `!*.example`.

Local toolchain as of this date: Temurin JDK 21.0.12 (user-scope,
`%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21.0.12.101-hotspot`), Maven 3.9.16 at
`%USERPROFILE%\Tools\apache-maven-3.9.16`, Node 24.16, Python 3.14, Docker Desktop 29.4.3,
Compose v5.1.4. `uv` and `gh` are NOT installed.

---

## 16. The 15 non-negotiable rules

1. LLM is never the source of truth. 2. LLM never mutates financial state. 3. Never invent
evidence. 4. No floating-point money. 5. No technology without a workload. 6. Simple correct
before distributed. 7. AI provider code isolated behind an abstraction. 8. Provenance +
auditability for every artifact. 9. All consumers tolerate duplicates. 10. Assume late and
out-of-order events. 11. Reproducible via deterministic seeds. 12. Measure performance; never
claim it. 13. Core financial domain stays in Java. 14. AI reasoning stays in Python.
15. The whole stack runs locally for ₹0.

---

## 17. Maintaining this file

Update when you: add/remove a service or route · change an enum, topic, or table · change the
readiness formula or a safety rule · change a cross-language type · resolve an item in §15.

Order of edit: change `PLATFORM-CONTRACT.md` (or `SHARED-LIBRARY-API.md`) **first**, then the
code, then the module's `context.md`, then this file. An ADR when the decision is worth
remembering.
