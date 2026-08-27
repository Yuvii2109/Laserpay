# PDEI — Master Project Context

> **Read this first in any new session.** It is the living index of the whole repository.
> Keep it updated as the project changes — it is the primary guide for reclaiming context.
>
> Last updated: 2026-08-27 · Baseline generation complete · 1,031 source files

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

Repo root: `c:\Users\Dell\OneDrive - Espalier\Desktop\Laserpay`. Not yet a git repo with commits.

---

## 1. Document hierarchy — what is authoritative

| Document | Role | Editable? |
|---|---|---|
| `planner/pre-dispute-evidence-intelligence-reference.md` | Original product brief (43 sections). The **why**. | Frozen — source of intent |
| `docs/PLATFORM-CONTRACT.md` | **NORMATIVE.** Every cross-service identifier: ports, topics, routes, enums, DB schema, readiness formula, AI contract, Temporal workflow, env vars. | Change deliberately; code follows it, never the reverse |
| `docs/SHARED-LIBRARY-API.md` | **NORMATIVE.** Exact class/method surface of the three shared Maven modules. | Same |
| `docs/architecture.md` | Narrative: the thesis, the funnel, three planes, per-technology justification, the 8 correctness properties. | Living |
| `docs/adr/` (10 ADRs) | Decisions with their forces and consequences. | Append; supersede rather than edit |
| `docs/event-catalog.md` | Every event with payload, producer, consumers, contradiction sources. | Living |
| `docs/testing-strategy.md` | What is tested at which level, plus the CI greps enforcing architectural rules. | Living |
| `docs/benchmark-plan.md` | Measure-don't-claim methodology + reporting template. | Living |
| `docs/demo-script.md` | 12-minute reproducible demo (seed 4281) + chaos matrix. | Living |
| `docs/glossary.md` | Domain vocabulary. | Living |
| `<module>/context.md` × 16 | Per-module deep context. | Living — update with the module |

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
├── schemas/                       35 JSON Schemas — events/ (32) + ai/ (3)
├── infra/                         22 files — docker-compose + all infra config
├── backend/                       655 files — Maven reactor `pdei-backend`, 12 modules
├── ai-reasoning-service/          58 files — Python FastAPI, package `pdei_ai`
├── frontend/                      223 source files — Next.js 15, `pdei-web`
├── scripts/                       13 dev scripts (.sh + .ps1 for Windows)
└── .github/workflows/ci.yml
```

Counts exclude `node_modules/`, `.next/`, `target/` — all gitignored build artifacts.

⚠️ **The repo lives inside a OneDrive-synced folder.** OneDrive tries to sync `node_modules`
(~32k files) and `.next` (~2k), which triggers bulk-delete prompts and slows builds. Exclude
them from sync, or move the repo to e.g. `C:\dev\Laserpay`. Maven `target/` will add tens of
thousands more on first `mvn package`.

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
| `frontend` | 3000 | Next.js | — |

### Infrastructure

Postgres 5432 (db/user/pass `pdei`) · Redis 6379 · Kafka `kafka:9092` internal / `29092` host ·
Kafka UI 8090 · MinIO 9000 API / 9001 console (`pdei-minio` / `pdei-minio-secret`) ·
Temporal 7233, UI 8233, namespace `pdei` · Prometheus 9090 · Grafana 3001 (admin/admin) ·
Loki 3100 · Tempo 3200 · OTel Collector 4317/4318.

Compose profiles: `core` (infra) · `app` (services) · `obs` (observability).

---

## 4. Backend — Maven reactor (`backend/`, 655 files, 73 test classes)

### 4.1 Shared library modules

**`platform-common`** — plain Java + Jackson, no Spring autoconfig, no JPA. Frozen API.
- `money.Money` — `(long amountMinor, String currency)`. Overflow-checked (`Math.addExact`),
  validates ISO-4217, throws `CurrencyMismatchException`, derives decimal exponent from
  `java.util.Currency` (never hardcodes /100). `toDisplayString()` is the *only* place a
  decimal point appears.
- `id.Ids` / `IdPrefix` / `SeededIdGenerator` — prefixed IDs (`MER- CUS- TX- PAY- ORD- SHP-
  DLV- REF- COM- EV- POL- DSP- CASE- INV- AUD- SIM-`); seedable for simulator determinism.
- `event.*` — `CanonicalEvent` (+Builder), `EventType`, `AggregateType`, `EventSource`,
  `RawEventEnvelope`, `DeadLetterEnvelope`, `AuditEvent`, `ActorType`.
- `domain.*` — the 14 shared enums (see §6).
- `kafka.Topics` / `ConsumerGroups` / `EventHeaders`.
- `hash.Hashes` (sha256, canonical-JSON hash, `chain()`), `json.Json` (the one ObjectMapper),
  `error.PdeiException` sealed hierarchy, `time.Clocks`, `metrics.MetricNames`.

**`platform-persistence`** — JPA entities, Spring Data repositories, Flyway.
- Migrations `V1__baseline` → `V10__fts` create **28 tables** (§7).
- `MoneyEmbeddable` maps `Money` to two columns.
- `ProcessedEventRepository.markProcessed(eventId, consumerGroup)` — native
  `INSERT … ON CONFLICT DO NOTHING`, returns `boolean firstTime`. **The canonical idempotency
  primitive.**
- `PersistenceAutoConfiguration` registered via `AutoConfiguration.imports`.

**`evidence-core`** — the deterministic domain engine, the intellectual heart.
Packages: `ai/ audit/ config/ dispute/ evidence/ model/ policy/ readiness/ safety/ search/
spi/ spi/jdbc/ spi/kafka/ storage/ timeline/ util/`
- `readiness.ReadinessEngine` — the scoring formula (§8). Pure; testable without a DB via an
  injected data provider port.
- `readiness.GapDetector` / `ContradictionDetector`.
- `policy.PolicyEngine` — reason-code → requirement matrix, action permission, thresholds.
- `safety.AiResultValidator` — the 7 rejection rules (§9). `safety.SafetyGate`.
- `ai.AiReasoningClient` (interface) / `HttpAiReasoningClient` / `AdmissionController`.
- `evidence.EvidenceService` / `EvidenceIntegrityService` / `EvidenceGraphService` /
  `EvidenceLineageService`.
- `storage.ObjectStore` → `MinioObjectStore`. `dispute.CaseAssemblyService`.
  `audit.AuditRecorder` (hash-chained). `search.EvidenceSearchService` (Postgres FTS).
- `model.*` — ~24 immutable records shared across services: `ReadinessSnapshot`, `ReadinessGap`,
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
**Every consumer is idempotent** — Redis `SETNX` on `pdei:idem:{eventId}` (7d TTL) plus
`processed_events` `ON CONFLICT DO NOTHING`.

Ordering guarantee: per-aggregate only. Cross-aggregate events *will* interleave — a
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

## 7. Database — schema `pdei`, 28 tables

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

## 8. Readiness scoring (deterministic — never model-derived)

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
no model client exists anywhere under `backend/`** — enforced by a CI grep. The AI service has
**no database credentials**; it sees only the curated `InvestigationContext` plus ten read-only
`GET` tools under `/api/v1/ai-tools/*` (token: header `X-PDEI-Service-Token`). The tool
executor structurally refuses non-GET requests and unknown tool names.

### FastAPI routes (prefix `/v1`)
`POST /investigate` · `POST /investigate/stream` (SSE) · `POST /admission/score` ·
`POST /narrative` · `GET /tools` · `GET /providers` · plus `/health`, `/ready`, `/metrics`.

### Reasoners (`pdei_ai/reasoners/`)
`base.py` (Protocol) · `gemini.py` · `mock.py` · `null.py` · `registry.py`.
Selected by `PDEI_AI_PROVIDER=gemini|mock|null`. **Dev default `mock`** — deterministic, seeded
from `investigationId`, no wall clock. Fallback chain `gemini → mock`. The whole stack works
with no API key.

### The 7 validation rules (`AiResultValidator`) — reject when ANY holds
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
escalate. Recorded as `aiInvoked:false` + `bypassReason` — the source of the AI-reduction metric.

---

## 10. Temporal

Namespace `pdei` · task queue `pdei-dispute-cases` · workflow id `case-{caseId}`.

`DisputeCaseWorkflow` — 12 steps: openCase → gatherEvidence → detectGaps →
awaitMissingEvidence (timer + signal, max 7d) → runAdmissionControl → investigate →
validateAndGate → awaitHumanApproval → prepareRepresentmentPackage → submitRepresentment →
followUp → closeCase.

Signals: `evidenceArrived`, `humanDecision`, `disputeUpdated`, `cancelCase`.
Queries: `getCaseState`, `getProgress`.
Retry: initial 1s, backoff 2.0, max 60s, 10 attempts; non-retryable `PolicyViolationException`,
`ValidationException`. Workflow code is deterministic — all side effects via activities.

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

1. **Idempotency** — N deliveries ≡ 1. Redis SETNX + `processed_events`.
2. **Out-of-order tolerance** — projections carry `last_event_occurred_at`; older events ignored.
3. **Replayability** — state is a fold over the log; replay reproduces identical scores.
4. **Evidence immutability** — new version supersedes, never overwrites.
5. **Integrity** — SHA-256 at write; re-hash detects corruption → `INVALIDATED` + audit.
6. **Deterministic readiness** — same inputs → same integer, always.
7. **AI groundedness** — every claim cited; unresolvable citations reject the result.
8. **Workflow durability** — killing the orchestrator loses nothing.

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

⚠️ Maven is **not** installed system-wide and `JAVA_HOME` is **not** set globally — set both, or
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

- **Response-shape drift** — frontend `PageResponse<T>` was `{items,total}` vs gateway
  `{content,totalElements,totalPages}`; `MerchantSummary` nested vs flat; `TransactionDetail`
  flat vs nested; `policies.list()` paginated vs bare list.
- **Tri-language type divergence** — `GapRef.evidenceId` / `ContradictionRef.left|right` carried
  a Pydantic `^EV-` regex while Java put entity IDs there; `missingEvidence` had three different
  types; `ShortCircuit` union incomplete in TS.
- **Silent data loss** — every `GAP_DETECTED` WS frame discarded (parser required `gapId`, the
  gateway never sent it).
- **Contract violations** — simulator `EventEmitter` partition key not `merchantId:aggregateId`;
  `pdei_events_processed_total` registered with two different tag-key sets.
- **Missing CORS** on `simulator-service` (browser calls :8088 cross-origin).

### First real build — 2026-08-28

CI failed on first push (5 red / 2 green). Root causes found by compiling locally; six defects,
all mechanical, none architectural:

| # | Defect | Blast radius |
|---|---|---|
| 1 | `--` inside an XML comment in `case-orchestrator-service/pom.xml` (6 banner comments) — non-parseable POM | **entire reactor**; nothing compiled |
| 2 | Unescaped backslashes, 7× in `Buckets.java`, `Text.java`, `BucketsTest.java` | `evidence-core` |
| 3 | `CoreErrors.upstream(msg)` called a constructor that needs `(upstream, msg)`; 9 call sites updated to name MinIO | `evidence-core` |
| 4 | `ExponentialBackOffWithMaxRetries` imported from `org.springframework.util.backoff`; it lives in `org.springframework.kafka.support` | 3 Kafka configs |
| 5 | `io.temporal:temporal-spring-boot-starter-alpha:1.25.1` never existed — `-alpha` was retired at 1.23.2, artifact renamed | orchestrator |
| 6 | `RawEventValidator` reported `required` violations against the containing object (`body`) instead of the missing field (`body.createdAt`) | ingestion API usability |

Two of these are worth remembering. **#5**: the parent POM already had the correct non-alpha
artifact in `dependencyManagement` while the child used `-alpha` — two agents, two answers,
invisible until resolution ran. **#6** was a genuine API defect, not a typo: networknt reports a
`required` violation against the *parent* object with the absent property in `getProperty()`,
so integrators would have been told "something is wrong with `body`". Fixed in the validator
(also covers `additionalProperties`), not by relaxing the test.

Also fixed: `ruff format` on 22 of 53 Python files (the AI-service CI failure), and
`Text.java` used `"\s+"`, which compiles — `\s` is a legal Java escape meaning a literal
space — but silently failed to collapse tabs and newlines. A latent bug no test would catch.

Infra CI failed on **shellcheck** alone (it exits non-zero on any finding): `C_DIM`/`C_BOLD`
flagged unused in `lib.sh` (they are used by sourcing scripts — fixed with `export`, which is
what the code meant), an `A && B || C` in `reset.sh` (SC2015 — rewritten as if/else), and an
unused `read` field in `smoke-test.sh`. hadolint already passed: every finding is warning/info,
below CI's `error` threshold. Compose config validates on all three profiles.

### ⚠️ The integration suite is a false green

`AbstractPostgresIntegrationTest.dockerAvailable()` gates the Testcontainers tests via
`@EnabledIf`. When Docker is unreachable they **skip silently**, so "Backend (integration)"
reports success having executed nothing:

```
EvidencePersistenceIntegrationTest       Tests run: 4, Skipped: 4   (0.002s)
ProcessedEventRepositoryIntegrationTest  Tests run: 5, Skipped: 5   (0.001s)
```

Fixed by making the guard return `true` when `CI=true` (GitHub sets it), so a runner without
Docker fails loudly instead of going green. **Local dev still skips gracefully.**

Locally the skip is caused by a Docker Desktop quirk, not by the code: Testcontainers probes
`\\.\pipe\docker_engine`, which Docker Desktop 29.4.3 answers with an empty HTTP 400 carrying
only `com.docker.desktop.address`; the CLI uses `dockerDesktopLinuxEngine` instead. `DOCKER_HOST`
override and a Testcontainers bump to 1.21.3 both failed to help, so the bump was reverted
(still 1.20.3). Server API is 1.54 with a 1.40 floor, so it is not version negotiation.
**Consequence: the integration tests have still never actually run — CI will be their first
real execution.**

### Open gaps / TODOs
- [x] ~~Backend never compiled~~ — **compiles clean; 505 unit tests pass** (2026-08-28).
- [ ] **Integration tests never actually executed** (see above). Verify on the next CI run.
- [ ] `-DskipUTs` / `-DskipITs` are not defined as properties in `backend/pom.xml`, and no module
      binds maven-failsafe (it is in `pluginManagement` only). The two CI matrix suites therefore
      run identically, and `*IntegrationTest.java` is picked up by **surefire**, not failsafe.
      Harmless today; tidy when convenient.
- [ ] Python suite (9 test files) never executed; `uv` not installed. Only `ruff` was verified.
- [ ] `docker compose up` never run end-to-end.
- [ ] Only one commit (`a03ee6b`); the CI fixes above are uncommitted (38 files).
- [ ] 16 MEDIUM/LOW audit findings logged but not fixed — re-run an audit to recover the list.
- [ ] `InvestigationEntity.missingEvidence` (jsonb) has **no readers or writers** anywhere —
      dead field, or a persistence path that was never wired. Decide which.
- [ ] `isEvidenceType()` in `frontend/src/app/cases/[caseId]/_components/AiReasoningTab.tsx`
      is a tautological guard (else-branch narrows to `never`). Harmless, but dead.
- [ ] `pdei_events_processed_total` tag *values* differ in style across `DisputeEventListener`
      call sites (`failure` vs `HANDLED`/`IGNORED`/`FAILED`). Keys match, so Micrometer is
      safe, but the label cardinality is untidy.
- [ ] `SimulatedNetworkSubmitter` is a named seam — real PSP submission out of scope.
- [ ] No OCR (deliberate — reference doc §25).
- [ ] Benchmarks unrun; `benchmarks/results/` does not exist yet.

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
