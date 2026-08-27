# Pre-Dispute Evidence Intelligence — Platform Contract (NORMATIVE)

> This file is the **single source of truth** for cross-service identifiers.
> Every service in this repository MUST conform to it. If code and this file
> disagree, the code is wrong. Derived from `planner/pre-dispute-evidence-intelligence-reference.md`.

Project codename: **PDEI** (Pre-Dispute Evidence Intelligence). Repo root: `Laserpay/`.

---

## 1. Naming & Coordinates

| Item | Value |
|---|---|
| Maven groupId | `com.laserpay.pdei` |
| Java base package | `com.laserpay.pdei` |
| Java version | 21 |
| Spring Boot | 3.3.5 |
| Maven parent artifactId | `pdei-backend` |
| Python package | `pdei_ai` |
| Python | 3.11+ (uv managed) |
| Node | 20+ |
| Frontend package name | `pdei-web` |
| Docker network | `pdei-net` |
| Docker compose project | `pdei` |
| Kubernetes namespace | `pdei` |

Per-service Java package roots:

| Module | Package root |
|---|---|
| platform-common | `com.laserpay.pdei.common` |
| platform-persistence | `com.laserpay.pdei.persistence` |
| evidence-core | `com.laserpay.pdei.core` |
| api-gateway-service | `com.laserpay.pdei.api` |
| ingestion-service | `com.laserpay.pdei.ingestion` |
| normalization-worker | `com.laserpay.pdei.normalization` |
| state-builder-worker | `com.laserpay.pdei.statebuilder` |
| readiness-worker | `com.laserpay.pdei.readiness` |
| case-orchestrator-service | `com.laserpay.pdei.orchestrator` |
| document-processor-service | `com.laserpay.pdei.docproc` |
| audit-service | `com.laserpay.pdei.audit` |
| simulator-service | `com.laserpay.pdei.simulator` |

---

## 2. Service Registry & Ports

| Service | Kind | Host port | Container port | Health |
|---|---|---|---|---|
| `api-gateway-service` | Spring Boot (web) | 8080 | 8080 | `/actuator/health` |
| `ingestion-service` | Spring Boot (web) | 8081 | 8081 | `/actuator/health` |
| `normalization-worker` | Spring Boot (worker) | 8082 | 8082 | `/actuator/health` |
| `state-builder-worker` | Spring Boot (worker) | 8083 | 8083 | `/actuator/health` |
| `readiness-worker` | Spring Boot (worker) | 8084 | 8084 | `/actuator/health` |
| `case-orchestrator-service` | Spring Boot + Temporal worker | 8085 | 8085 | `/actuator/health` |
| `document-processor-service` | Spring Boot (worker+web) | 8086 | 8086 | `/actuator/health` |
| `audit-service` | Spring Boot (worker+web) | 8087 | 8087 | `/actuator/health` |
| `simulator-service` | Spring Boot (web) | 8088 | 8088 | `/actuator/health` |
| `ai-reasoning-service` | Python FastAPI | 8000 | 8000 | `/health` |
| `frontend` (`pdei-web`) | Next.js | 3000 | 3000 | `/api/health` |

Infrastructure ports:

| Component | Host | Notes |
|---|---|---|
| PostgreSQL | 5432 | db `pdei`, user `pdei`, pass `pdei` (dev only) |
| Redis | 6379 | no auth in dev |
| Kafka (KRaft, single broker) | internal `kafka:9092`, host `29092` | |
| Kafka UI | 8090 | |
| MinIO API | 9000 | key `pdei-minio` / secret `pdei-minio-secret` |
| MinIO Console | 9001 | |
| Temporal server | 7233 | namespace `pdei` |
| Temporal UI | 8233 | |
| Prometheus | 9090 | |
| Grafana | 3001 | admin/admin |
| Loki | 3100 | |
| Tempo (traces) | 3200 | OTLP in via collector |
| OTel Collector | 4317 (gRPC), 4318 (HTTP) | |

Actuator on every Spring service exposes: `health,info,prometheus,metrics,loggers`.
Prometheus scrape path for Spring services: `/actuator/prometheus`.

---

## 3. Canonical Event Envelope

All events on canonical topics use this JSON envelope. Field names are exact.

```json
{
  "eventId": "uuid string",
  "eventType": "PaymentCaptured",
  "schemaVersion": 1,
  "aggregateType": "PAYMENT",
  "aggregateId": "PAY-000123",
  "merchantId": "MER-0001",
  "correlationId": "uuid string",
  "causationId": "uuid string or null",
  "occurredAt": "2026-08-26T10:15:30.123Z",
  "observedAt": "2026-08-26T10:15:31.004Z",
  "source": "PSP_ADAPTER",
  "idempotencyKey": "stable string",
  "payload": {}
}
```

Java type: `com.laserpay.pdei.common.event.CanonicalEvent` (record, immutable, Jackson).
Python type: `pdei_ai.models.events.CanonicalEvent` (Pydantic v2).
TypeScript type: `CanonicalEvent` in `frontend/src/lib/types/events.ts`.

`source` enum: `PSP_ADAPTER, ORDER_SYSTEM, LOGISTICS, CRM, SIMULATOR, INTERNAL, MERCHANT_PORTAL`.

### 3.1 Canonical event types (enum `EventType`)

```
PAYMENT:       PaymentCreated, PaymentAuthorized, PaymentCaptured, PaymentFailed
ORDER:         OrderCreated, OrderFulfilled, OrderCancelled
SHIPMENT:      ShipmentCreated, ShipmentDispatched, ShipmentDelivered
REFUND:        RefundCreated, RefundProcessed
COMMUNICATION: CommunicationCreated, CommunicationReceived
EVIDENCE:      EvidenceAdded, EvidenceExpired, EvidenceInvalidated
DISPUTE:       DisputeCreated, DisputeUpdated, DisputeClosed
READINESS:     ReadinessRecomputed, ReadinessGapDetected      (internal, readiness-worker)
CASE:          CaseOpened, CaseEvidenceAttached, CaseInvestigated, CasePrepared,
               CaseEscalated, CaseSubmitted, CaseClosed        (internal, orchestrator)
AUDIT:         AuditRecorded                                    (internal)
```

`aggregateType` enum: `MERCHANT, CUSTOMER, TRANSACTION, PAYMENT, ORDER, SHIPMENT, DELIVERY, REFUND, COMMUNICATION, EVIDENCE, POLICY, DISPUTE, CASE`.

---

## 4. Kafka Topics

| Topic | Partitions | Producer | Consumers | Payload |
|---|---|---|---|---|
| `pdei.raw.events.v1` | 12 | ingestion-service, simulator-service | normalization-worker | `RawEventEnvelope` (source-shaped) |
| `pdei.canonical.events.v1` | 12 | normalization-worker | state-builder-worker, audit-service, document-processor-service | `CanonicalEvent` |
| `pdei.evidence.events.v1` | 12 | state-builder-worker, document-processor-service | readiness-worker, audit-service | `CanonicalEvent` (EVIDENCE types) |
| `pdei.readiness.events.v1` | 12 | readiness-worker | api-gateway-service, audit-service | `CanonicalEvent` (READINESS types) |
| `pdei.dispute.events.v1` | 12 | state-builder-worker, ingestion-service | case-orchestrator-service, audit-service | `CanonicalEvent` (DISPUTE types) |
| `pdei.case.events.v1` | 12 | case-orchestrator-service | api-gateway-service, audit-service | `CanonicalEvent` (CASE types) |
| `pdei.audit.events.v1` | 6 | all services | audit-service | `AuditEvent` |
| `pdei.dlq.v1` | 6 | all consumers | (manual/replay) | `DeadLetterEnvelope` |

**Partition key (mandatory):** `merchantId + ":" + aggregateId`.
**Consumer groups:** `pdei-<service-name>` e.g. `pdei-normalization-worker`.
All consumers MUST be idempotent (dedupe on `eventId` via Redis SETNX + Postgres `processed_events`).

---

## 5. PostgreSQL Schema (Flyway, owned by `platform-persistence`)

Schema name: `pdei`. Migrations in `backend/platform-persistence/src/main/resources/db/migration`.

```
V1__baseline.sql          merchants, customers, processed_events
V2__transactions.sql      transactions, payments, orders, order_lines, refunds,
                          shipments, deliveries, communications
V3__evidence.sql          evidence, evidence_versions, evidence_relationships
V4__policy.sql            policies, policy_versions, evidence_requirements
V5__disputes.sql          disputes, dispute_cases, case_evidence
V6__readiness.sql         readiness_snapshots, readiness_gaps
V7__investigations.sql    investigations, investigation_findings, ai_admission_log
V8__audit.sql             audit_events (hash-chained)
V9__simulation.sql        simulation_runs, chaos_injections
V10__fts.sql              tsvector columns + GIN indexes for evidence search
```

**Money rule (non-negotiable):** every monetary column is
`amount_minor BIGINT NOT NULL` + `currency CHAR(3) NOT NULL`. No FLOAT/DOUBLE/NUMERIC for money.
Java type: `com.laserpay.pdei.common.money.Money` (record of `long amountMinor`, `String currency`).
TypeScript: `{ amountMinor: number; currency: string }` — format only at render time.

Time rule: all timestamps `TIMESTAMPTZ`, stored UTC. Java `Instant`. Never `LocalDateTime`.

ID conventions (human-readable prefixed, `VARCHAR(64)` primary keys):
`MER-`, `CUS-`, `TX-`, `PAY-`, `ORD-`, `SHP-`, `DLV-`, `REF-`, `COM-`, `EV-`, `POL-`, `DSP-`, `CASE-`, `INV-`, `AUD-`, `SIM-`.

---

## 6. Core Domain Enums (shared across Java / Python / TS — spell identically)

```
EvidenceType:      PAYMENT_PROOF, INVOICE, ORDER_RECORD, SHIPPING_RECORD,
                   DELIVERY_PROOF, REFUND_RECEIPT, CUSTOMER_COMMUNICATION,
                   MERCHANT_POLICY, TERMS_OF_SERVICE, AVS_CVV_RESULT,
                   DEVICE_FINGERPRINT, PRIOR_TRANSACTION_HISTORY, SIGNED_CONTRACT

EvidenceStatus:    PENDING, ACTIVE, EXPIRING, EXPIRED, INVALIDATED, SUPERSEDED

EvidenceSource:    PSP_ADAPTER, ORDER_SYSTEM, LOGISTICS, CRM, DOCUMENT_UPLOAD,
                   MERCHANT_PORTAL, SIMULATOR, INTERNAL_DERIVED

DisputeReasonCode: GOODS_NOT_RECEIVED, SERVICE_NOT_RENDERED, PRODUCT_NOT_AS_DESCRIBED,
                   DUPLICATE_PROCESSING, CREDIT_NOT_PROCESSED, SUBSCRIPTION_CANCELLED,
                   FRAUDULENT_TRANSACTION, UNRECOGNIZED_TRANSACTION,
                   INCORRECT_AMOUNT, PAID_BY_OTHER_MEANS

DisputeStatus:     OPEN, EVIDENCE_GATHERING, UNDER_INVESTIGATION, AWAITING_HUMAN_REVIEW,
                   REPRESENTMENT_PREPARED, SUBMITTED, WON, LOST, EXPIRED, WITHDRAWN

CaseStatus:        CREATED, ASSEMBLING, INVESTIGATING, AWAITING_EVIDENCE,
                   AWAITING_APPROVAL, PREPARED, SUBMITTED, CLOSED, FAILED

ReadinessBand:     READY (>=90), NEARLY_READY (75-89), AT_RISK (50-74), NOT_READY (<50)

RequirementStrength: MANDATORY, RECOMMENDED, OPTIONAL, PROHIBITED

GapType:           MISSING, EXPIRED, EXPIRING_SOON, CONTRADICTORY,
                   UNVERIFIABLE_PROVENANCE, LOW_QUALITY, VERSION_CONFLICT

GapSeverity:       LOW, MEDIUM, HIGH, CRITICAL

InvestigationClassification: DEFENDABLE, WEAK, INDEFENSIBLE, INSUFFICIENT_EVIDENCE, AMBIGUOUS

RecommendedAction: PREPARE_REPRESENTMENT, GATHER_MORE_EVIDENCE, ACCEPT_LIABILITY,
                   ESCALATE_TO_HUMAN, REQUEST_POLICY_REVIEW

SafetyDecision:    ALLOW, ALLOW_WITH_REVIEW, DENY

ChaosType:         DUPLICATE_EVENT, DELAYED_EVENT, OUT_OF_ORDER_EVENT, DROP_EVENT,
                   DELETE_EVIDENCE, CORRUPT_EVIDENCE_HASH, EXPIRE_EVIDENCE,
                   CONFLICTING_EVIDENCE, KILL_WORKER, RESTART_CONSUMER, REPLAY_EVENTS,
                   INJECT_DISPUTE, SLOW_CONSUMER
```

---

## 7. Readiness Scoring (deterministic — `evidence-core`)

`ReadinessEngine.compute(transactionId, reasonCode?) -> ReadinessSnapshot`

```
base = 100 * (SUM weight(satisfied mandatory) + 0.5 * SUM weight(satisfied recommended))
           / (SUM weight(all mandatory)       + 0.5 * SUM weight(all recommended))
penalties:
  -15 per CONTRADICTORY gap
  -10 per EXPIRED mandatory evidence
  -5  per EXPIRING_SOON mandatory evidence (expiry within 7 days)
  -20 if any UNVERIFIABLE_PROVENANCE on mandatory evidence
score = clamp(round_half_up(base - penalties), 0, 100)
```

Default requirement weights: MANDATORY=3, RECOMMENDED=2, OPTIONAL=1, PROHIBITED=0.
When no `reasonCode` is supplied, use the merchant's **baseline requirement profile**
(union of MANDATORY requirements across that merchant's top reason codes).
`ReadinessBand` derives from the final score using the bands in §6.

Readiness recomputation triggers: any `EVIDENCE` event, any state change on a linked
entity, policy version change, and a nightly sweep for expiry transitions.

---

## 8. REST API Surface

### 8.1 `api-gateway-service` — base `http://localhost:8080/api/v1`

```
GET    /health/ready
GET    /merchants                                  list (page,size)
GET    /merchants/{merchantId}
GET    /merchants/{merchantId}/summary             control-tower KPIs
GET    /transactions                               ?merchantId&band&from&to&page&size
GET    /transactions/{transactionId}
GET    /transactions/{transactionId}/timeline      unified event+evidence timeline
GET    /transactions/{transactionId}/readiness     current ReadinessSnapshot
POST   /transactions/{transactionId}/readiness/recompute
GET    /transactions/{transactionId}/evidence
GET    /transactions/{transactionId}/graph         evidence graph (nodes+edges)
GET    /evidence                                   ?merchantId&type&status&q (FTS)
GET    /evidence/{evidenceId}
GET    /evidence/{evidenceId}/versions
GET    /evidence/{evidenceId}/lineage
GET    /evidence/{evidenceId}/download             302 -> MinIO presigned URL
POST   /evidence                                   multipart upload (merchant portal)
POST   /evidence/{evidenceId}/verify               re-hash + integrity check
GET    /disputes                                   ?merchantId&status&reasonCode
GET    /disputes/{disputeId}
POST   /disputes                                   manual/injected dispute creation
GET    /cases                                      ?status&merchantId
GET    /cases/{caseId}
GET    /cases/{caseId}/xray                        full Case X-Ray payload
POST   /cases/{caseId}/approve                     human approval (Temporal signal)
POST   /cases/{caseId}/reject
POST   /cases/{caseId}/submit
GET    /cases/{caseId}/package                     representment package manifest
GET    /investigations/{investigationId}
GET    /policies                                   ?merchantId
GET    /policies/{policyId}
GET    /policies/{policyId}/requirements
PUT    /policies/{policyId}                        new version (immutable history)
GET    /requirements?reasonCode=GOODS_NOT_RECEIVED
GET    /audit?entityId=&entityType=&page=
GET    /audit/verify-chain?merchantId=
GET    /gaps                                       ?merchantId&type&severity  (at-risk feed)
GET    /metrics/funnel                             events->candidates->ambiguous->AI->human
```

Streaming:

```
WS   /ws/control-tower?merchantId=...     server->client push, JSON frames
SSE  /api/v1/stream/events?merchantId=... canonical event tail
SSE  /api/v1/stream/cases/{caseId}        case progress
```

WebSocket frame envelope:
`{ "type": "READINESS_UPDATED"|"EVIDENCE_ADDED"|"DISPUTE_CREATED"|"CASE_UPDATED"|"GAP_DETECTED"|"CHAOS_INJECTED"|"HEARTBEAT", "at": iso8601, "merchantId": "...", "data": {} }`

### 8.2 `ingestion-service` — base `http://localhost:8081/ingest/v1`

```
POST /events            single raw event  (header: Idempotency-Key)
POST /events/batch      array, max 1000
POST /events/{sourceSystem}/webhook   source-specific webhook intake
GET  /schemas           registered source schemas
GET  /stats             accepted/rejected/deduped counters
```

Responses: `202 Accepted` with `{ "accepted": n, "rejected": [...], "duplicates": n }`.

### 8.3 `document-processor-service` — `http://localhost:8086/docproc/v1`

```
POST /extract           {objectKey} -> {text, metadata, pageCount, sha256}
POST /reprocess/{evidenceId}
GET  /stats
```

### 8.4 `audit-service` — `http://localhost:8087/audit/v1`

```
GET  /events            ?entityType&entityId&actor&from&to
GET  /chain/verify      recompute hash chain, returns first divergence if any
GET  /export            NDJSON export
```

### 8.5 `simulator-service` — `http://localhost:8088/sim/v1`

```
POST /runs              {seed, merchants, transactions, days, disputeRate, failureProfile} -> runId
GET  /runs              list
GET  /runs/{runId}      progress + stats
POST /runs/{runId}/stop
POST /chaos             {type: ChaosType, target: {...}, delayMs?, count?} -> injectionId
GET  /chaos             injection history
POST /replay            {topic, fromOffset|fromTimestamp, merchantId?}
GET  /scenarios         curated demo scenarios
POST /scenarios/{key}/run
```

### 8.6 `ai-reasoning-service` (Python FastAPI) — `http://localhost:8000`

```
GET  /health
GET  /ready
POST /v1/investigate            InvestigationContext -> InvestigationResult
POST /v1/investigate/stream     SSE step stream
POST /v1/admission/score        -> {admit: bool, priority: 0-100, reason}
POST /v1/narrative              evidence-backed representment narrative
GET  /v1/tools                  tool manifest exposed to the model
GET  /v1/providers              active reasoner + fallback chain
GET  /metrics                   Prometheus
```

Callback direction: the AI service calls **back** into `api-gateway-service` read-only
tool endpoints under `/api/v1/ai-tools/*` using a service token (header `X-PDEI-Service-Token`).

```
GET /api/v1/ai-tools/transaction/{id}
GET /api/v1/ai-tools/order/{id}
GET /api/v1/ai-tools/shipment/{id}
GET /api/v1/ai-tools/refund/{id}
GET /api/v1/ai-tools/evidence/{id}
GET /api/v1/ai-tools/evidence/related?transactionId=
GET /api/v1/ai-tools/contradictions?transactionId=
GET /api/v1/ai-tools/policy/applicable?merchantId=&reasonCode=
GET /api/v1/ai-tools/requirements?reasonCode=
GET /api/v1/ai-tools/timeline/{transactionId}
```

These endpoints are **read-only by construction** (no POST/PUT/DELETE under `/ai-tools`).

---

## 9. AI Contract

### 9.1 `InvestigationContext` (request to AI service)

```json
{
  "investigationId": "INV-...", "caseId": "CASE-...", "disputeId": "DSP-...",
  "merchantId": "MER-...", "transactionId": "TX-...",
  "reasonCode": "GOODS_NOT_RECEIVED",
  "disputeAmount": {"amountMinor": 1299900, "currency": "INR"},
  "deadlineAt": "2026-09-10T00:00:00Z",
  "transactionSummary": {},
  "evidence": [{"evidenceId":"EV-...","type":"DELIVERY_PROOF","status":"ACTIVE",
                "sha256":"...","createdAt":"...","summary":"...","version":2}],
  "requirements": [{"type":"DELIVERY_PROOF","strength":"MANDATORY","satisfied":true}],
  "gaps": [{"type":"MISSING","evidenceType":"CUSTOMER_COMMUNICATION","severity":"MEDIUM"}],
  "contradictions": [{"left":"EV-1","right":"EV-2","field":"deliveredAt","detail":"..."}],
  "policyConstraints": {"autoPrepareMinConfidence": 0.90, "maxContradictions": 0,
                        "prohibitedEvidenceTypes": []},
  "timeline": [{"at":"...","eventType":"ShipmentDelivered","summary":"..."}],
  "historicalContext": {"merchantWinRate": 0.71, "similarCases": 14}
}
```

### 9.2 `InvestigationResult` (response — schema-constrained, Pydantic + JSON Schema)

```json
{
  "investigationId": "INV-...",
  "classification": "DEFENDABLE",
  "confidence": 0.973,
  "supportingEvidence": ["EV-1092", "EV-8821"],
  "missingEvidence": [],
  "contradictions": [],
  "reasoningSummary": "...",
  "narrative": "...",
  "recommendedAction": "PREPARE_REPRESENTMENT",
  "citations": [{"claim": "...", "evidenceId": "EV-1092"}],
  "modelMetadata": {"provider":"gemini","model":"gemini-3.5-flash-lite",
                    "promptTokens":0,"completionTokens":0,"latencyMs":0,"attempt":1}
}
```

JSON Schema file: `schemas/ai/investigation-result.schema.json`.

### 9.3 Validation gate (Java side, `evidence-core` -> `AiResultValidator`)

Reject the result when ANY holds:

1. an `evidenceId` in `supportingEvidence`/`citations` does not exist in Postgres;
2. an evidence item is not linked to this case's transaction;
3. `recommendedAction` is not permitted by the applicable policy;
4. `confidence < policy.autoPrepareMinConfidence` and action is `PREPARE_REPRESENTMENT`;
5. `contradictions.length > policy.maxContradictions` and action is `PREPARE_REPRESENTMENT`;
6. any prohibited evidence type appears in `supportingEvidence`;
7. `classification` is `DEFENDABLE` while a MANDATORY requirement is unsatisfied.

Rejection -> `SafetyDecision.DENY` -> route to `AWAITING_HUMAN_REVIEW`, always audited.
Metric: `pdei_ai_unsupported_claims_total`.

### 9.4 Admission control (who gets sent to Gemini)

```
priority = 0.40*normalizedFinancialImpact
         + 0.25*deadlineUrgency        (1.0 if <48h remaining)
         + 0.20*ambiguityScore         (contradictions + gap count, normalized)
         + 0.15*(1 - deterministicConfidence)
admit if priority >= 55 AND redis token-bucket allows AND deterministic path unresolved.
```

Redis keys: `pdei:ai:budget:{yyyy-MM-dd}` (daily call budget), `pdei:ai:bucket` (rate limit).
Deterministic short-circuits that MUST bypass AI entirely:

- all MANDATORY requirements satisfied, zero contradictions -> auto `PREPARE_REPRESENTMENT`;
- zero evidence present at all -> `ACCEPT_LIABILITY` recommendation to human;
- dispute already past deadline -> `ESCALATE_TO_HUMAN`.

### 9.5 Provider abstraction

`EvidenceReasoner` protocol (Python) with implementations `GeminiReasoner`,
`MockReasoner` (deterministic, seeded), `NullReasoner`. Selected by env
`PDEI_AI_PROVIDER=gemini|mock|null`. Default in dev: `mock`.
Java side never imports a Gemini SDK; it only calls the FastAPI service through
`AiReasoningClient` (interface) in `evidence-core`.

---

## 10. Temporal

Namespace `pdei`. Task queue `pdei-dispute-cases`.

Workflow: `DisputeCaseWorkflow` (workflow id = `case-{caseId}`)

```
1.  openCase                      activity
2.  gatherEvidence                activity (idempotent, retryable)
3.  detectGaps                    activity
4.  awaitMissingEvidence          timer + signal `evidenceArrived`, max 7 days
5.  runAdmissionControl           activity
6.  investigate                   activity (calls ai-reasoning-service; may be skipped)
7.  validateAndGate               activity (AiResultValidator + policy)
8.  awaitHumanApproval            signal `humanDecision`, timeout -> escalate
9.  prepareRepresentmentPackage   activity (assembles MinIO bundle + manifest)
10. submitRepresentment           activity
11. followUp                      timer loop until DisputeClosed signal or deadline
12. closeCase                     activity
```

Signals: `evidenceArrived`, `humanDecision`, `disputeUpdated`, `cancelCase`.
Queries: `getCaseState`, `getProgress`.
Activity retry policy: initial 1s, backoff 2.0, max interval 60s, max attempts 10,
non-retryable: `PolicyViolationException`, `ValidationException`.

---

## 11. MinIO Layout

Bucket `pdei-evidence` (versioning ON).

```
{merchantId}/{transactionId}/{evidenceType}/{evidenceId}/v{version}/{filename}
```

Bucket `pdei-packages` for representment bundles:

```
{merchantId}/{caseId}/representment-{caseId}-v{n}.zip
{merchantId}/{caseId}/manifest.json
```

Every object gets user metadata: `x-amz-meta-sha256`, `x-amz-meta-source-event-id`,
`x-amz-meta-evidence-id`, `x-amz-meta-version`.

---

## 12. Redis Key Namespace

```
pdei:idem:{eventId}                     event dedupe          TTL 7d
pdei:readiness:{transactionId}          cached snapshot JSON  TTL 10m
pdei:case:{caseId}:state                hot case state        TTL 24h
pdei:lock:{resource}                    distributed lock      TTL 30s
pdei:ratelimit:{merchantId}:{window}    API rate limit
pdei:ai:budget:{date} / pdei:ai:bucket  AI admission control
pdei:sim:run:{runId}                    simulator progress
pdei:stream:offsets:{consumerGroup}     replay bookmarks
```

---

## 13. Observability Conventions

Metric prefix `pdei_`. Required metrics (Micrometer / prometheus_client):

```
pdei_events_ingested_total{source,type}
pdei_events_processed_total{service,type,outcome}
pdei_events_duplicate_total{service}
pdei_event_processing_latency_seconds{service,type}
pdei_kafka_consumer_lag{group,topic}
pdei_readiness_computation_seconds
pdei_readiness_score{merchant}
pdei_evidence_total{type,status}
pdei_case_assembly_seconds
pdei_ai_requests_total{provider,outcome}
pdei_ai_admission_total{decision}
pdei_ai_latency_seconds{provider}
pdei_ai_unsupported_claims_total
pdei_policy_gate_total{decision}
pdei_workflow_failures_total{workflow}
pdei_chaos_injections_total{type}
```

Tracing: OTLP -> collector `http://otel-collector:4318`. Service name = module name.
Trace context propagated through Kafka headers (`traceparent`).
Structured JSON logs -> stdout -> promtail -> Loki. Every log line carries
`traceId`, `spanId`, `merchantId`, `correlationId` when available.

---

## 14. Frontend Route Map (`frontend`, Next.js App Router)

```
/                                   redirect -> /control-tower
/control-tower                      Merchant Control Tower (KPIs, readiness dist., at-risk feed)
/transactions                       searchable table + readiness band filter
/transactions/[transactionId]       transaction detail + timeline + evidence graph
/evidence                           evidence explorer (FTS, type/status filters)
/evidence/[evidenceId]              evidence detail: versions, lineage, hash, provenance
/disputes                           dispute list
/disputes/[disputeId]               dispute detail
/cases                              case queue (status swimlanes)
/cases/[caseId]                     Case X-Ray: timeline | graph | evidence | AI reasoning | gate | package
/policies                           policy + requirement matrix (versioned)
/simulation                         Simulation & Chaos Console
/observability                      funnel + metrics summary
/settings                           merchant/service config
/api/health                         Next route handler
```

State: TanStack Query for server state, Zustand for UI state, native WebSocket hook
`useControlTowerSocket`. Styling: Tailwind + shadcn/ui. Charts: Recharts.
All API access goes through `frontend/src/lib/api/client.ts` (typed, base URL from
`NEXT_PUBLIC_API_BASE_URL`, default `http://localhost:8080/api/v1`).

---

## 15. Environment Variables (shared names)

```
PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei
PDEI_POSTGRES_USER=pdei
PDEI_POSTGRES_PASSWORD=pdei
PDEI_KAFKA_BOOTSTRAP=kafka:9092
PDEI_REDIS_URL=redis://redis:6379
PDEI_MINIO_ENDPOINT=http://minio:9000
PDEI_MINIO_ACCESS_KEY=pdei-minio
PDEI_MINIO_SECRET_KEY=pdei-minio-secret
PDEI_TEMPORAL_TARGET=temporal:7233
PDEI_TEMPORAL_NAMESPACE=pdei
PDEI_AI_SERVICE_URL=http://ai-reasoning-service:8000
PDEI_AI_PROVIDER=mock
PDEI_API_BASE_URL=http://api-gateway-service:8080
PDEI_SERVICE_TOKEN=dev-service-token
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.5-flash-lite
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_SERVICE_NAME=<module-name>
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws/control-tower
```

---

## 16. Repository Layout

```
Laserpay/
├── context.md                     master project context (living document)
├── README.md
├── planner/                       original reference doc
├── docs/                          PLATFORM-CONTRACT.md, architecture, ADRs, runbooks
├── schemas/                       JSON Schemas: events/, ai/
├── infra/                         docker-compose + all infra config
├── backend/                       Maven multi-module reactor (pdei-backend)
│   ├── pom.xml
│   ├── platform-common/
│   ├── platform-persistence/
│   ├── evidence-core/
│   ├── api-gateway-service/
│   ├── ingestion-service/
│   ├── normalization-worker/
│   ├── state-builder-worker/
│   ├── readiness-worker/
│   ├── case-orchestrator-service/
│   ├── document-processor-service/
│   ├── audit-service/
│   └── simulator-service/
├── ai-reasoning-service/          Python FastAPI (uv)
├── frontend/                      Next.js + TypeScript
├── scripts/                       dev helper scripts
└── .github/workflows/             CI
```

Every service/module directory MUST contain its own `context.md` describing:
purpose, responsibilities, package/file layout, inbound/outbound contracts,
config, dependencies, extension points, and known gaps/TODOs.

---

## 17. Non-Negotiable Rules (from reference §39)

1. The LLM is never the source of truth.
2. The LLM never mutates financial state.
3. Never invent evidence — unsupported claims are rejected.
4. No floating-point money, ever.
5. No technology without a workload that needs it.
6. Simple correct implementation before distributed complexity.
7. AI provider code isolated behind an abstraction.
8. Provenance + auditability preserved for every artifact.
9. All consumers tolerate duplicates.
10. Assume late and out-of-order events.
11. Reproducible workloads via deterministic seeds.
12. Measure performance; never claim it.
13. Core financial domain stays in Java.
14. AI reasoning stays isolated in Python.
15. The whole stack runs locally for zero cost.
