# api-gateway-service - module context

> Maven artifact `api-gateway-service`, package root `com.laserpay.pdei.api`, port **8080**.
> Implements section 8.1 (and the callback surface of 8.6) of `docs/PLATFORM-CONTRACT.md`.
> That document and `docs/SHARED-LIBRARY-API.md` are normative; where this file and they
> disagree, they win.

---

## 1. Purpose

api-gateway-service is **the frontend's only backend** and **the AI service's only way back into the
platform**. Everything the Next.js app renders comes through here, and every fact the Python
reasoner is allowed to know it fetches from here.

The module owns no domain logic. Not "very little" - none. The readiness formula lives in
`ReadinessEngine`, the seven safety rules in `AiResultValidator`, evidence versioning in
`EvidenceService`, package assembly in `CaseAssemblyService`. This module decides which of those to
call, turns the result into JSON, and enforces the things that are genuinely HTTP concerns:
pagination, status codes, CORS, rate limits, correlation ids, authentication of the AI callback, and
the live push of events to a browser.

Three properties are structural rather than conventional, and are worth stating plainly because they
are what the code is shaped around:

1. **The AI tool surface cannot mutate anything.** `AiToolsController` contains no mutating mapping
   at all, and a reflection test fails the build if one is ever added.
2. **Money never becomes a decimal.** Every amount that crosses this boundary is
   `(amountMinor, currency)` in both directions, all the way to the browser.
3. **Every error names its correlation id.** Including the unexpected ones, including the ones a
   filter rejects before any handler runs.

---

## 2. Responsibilities

| Area | What this module owns |
|---|---|
| REST surface | Every route of contract section 8.1, correct verbs, `PageResponse` envelope |
| AI callback surface | The ten read-only `/api/v1/ai-tools/*` lookups and their service-token guard |
| Live push | WebSocket `/ws/control-tower`, two SSE streams, the Kafka fan-out consumer, heartbeats |
| Error translation | The `PdeiException` hierarchy and Spring MVC failures to `ErrorResponse` |
| Request hygiene | Correlation id, per-merchant rate limit, CORS, multipart limits |
| Orchestration | Thin composition over evidence-core services and platform-persistence repositories |
| Human case decisions | Approve / reject / submit, signalled to Temporal with a deterministic local fallback |

**Explicitly not owned here:** the readiness formula, gap and contradiction detection, policy
resolution, safety validation, admission control, evidence lifecycle, package assembly (all
`evidence-core`); Flyway migrations and JPA entities (`platform-persistence`); event normalisation
and state building (the workers); Temporal workflows (`case-orchestrator-service`); any prompt,
model name or AI SDK (`ai-reasoning-service`, Python).

---

## 3. File-by-file map

### Root

| File | Notes |
|---|---|
| `ApiGatewayApplication` | `@SpringBootApplication`, `@EnableScheduling` (for the heartbeat), `@EnableConfigurationProperties(ApiProperties)` |
| `pom.xml` | web, websocket, validation, actuator, data-redis, spring-kafka, micrometer-prometheus, springdoc, OTel starter; test: starter-test + Testcontainers |
| `Dockerfile` | three stages, `eclipse-temurin:21-jre` runtime, non-root uid 10001, layered jar |

### `api.config`

| File | Notes |
|---|---|
| `ApiProperties` | prefix `pdei.api`; nested `Cors`, `RateLimit`, `Stream`, `Orchestrator`, `Paging` |
| `OpenApiConfig` | OpenAPI 3 document, two groups (`merchant`, `ai-tools`), `ServiceToken` security scheme |
| `CorsConfig` | `WebMvcConfigurer` **bean** (not an implemented interface) so it stays out of MockMvc slices |
| `JacksonConfig` | `@Primary ObjectMapper` = `Json.mapper()`, plus the matching message converter |
| `CorrelationIdFilter` | reads or mints `X-Correlation-Id`, binds MDC, echoes the header, always clears |
| `RateLimitFilter` | fixed window on `pdei:ratelimit:{merchantId}:{window}`, fails **open** |
| `WebFilterConfig` | registers the three filters with explicit orders and URL patterns |

### `api.security`

| File | Notes |
|---|---|
| `ServiceTokenFilter` | guards `/api/v1/ai-tools/*`; constant-time compare against `PDEI_SERVICE_TOKEN`; 401 otherwise; refuses everything when the token is unconfigured |

### `api.error`

| File | Notes |
|---|---|
| `GlobalExceptionHandler` | the whole `PdeiException` hierarchy plus MVC/bean-validation failures to `ErrorResponse`; correlation id on every response; generic message on 500 |

### `api.dto`

Request/response records. Everything that already exists in `evidence-core` is reused verbatim
rather than re-declared (`EvidenceView`, `ReadinessSnapshot`, `ReadinessGap`, `EvidenceGraph`,
`CaseView`, `CaseXRay`, `DisputeView`, `PackageManifest`, `TransactionFacts`, `TimelineEntry`,
`PolicyView`, `RequirementSpec`, `IntegrityReport`, `ChainVerification`, `FunnelMetrics`,
`AuditEvent`, `Money`).

| File | Notes |
|---|---|
| `PageResponse<T>` | `content`, `page`, `size`, `totalElements`, `totalPages`; factories for `Page`, `SearchPage` and port slices |
| `HealthResponse` | READY / DEGRADED / NOT_READY plus a per-dependency map |
| `MerchantResponse`, `MerchantSummaryResponse` | directory row and the control-tower KPI block |
| `TransactionResponse`, `TransactionDetailResponse` | list row and detail payload |
| `TimelineResponse` | timeline entries plus a count and a generation instant |
| `EvidenceVersionsResponse` | the evidence row chain **and** the stored-object ledger |
| `EvidenceUploadRequest` | the JSON `metadata` part of the multipart upload |
| `CreateDisputeRequest` | `amountMinor` + `currency`; maps to `CreateDisputeCommand` |
| `CaseDecisionRequest`, `CaseDecisionResponse` | human decision in and out; `deliveredTo` says which path ran |
| `PolicyUpsertRequest` | new policy version; unset fields fall back to the seeded defaults |
| `RequirementsResponse` | requirements plus the policy version that answered and `defaultPolicy` |
| `InvestigationResponse` | model proposal, safety verdict and per-claim findings side by side |
| `FunnelResponse` | `FunnelMetrics` plus differenced stages with conversion ratios |
| `ContradictionsResponse`, `RelatedEvidenceResponse` | AI tool payloads |

### `api.service`

| File | Notes |
|---|---|
| `MerchantQueryService` | directory + KPI aggregation over five repositories |
| `TransactionQueryService` | search, detail, timeline, readiness read and recompute, evidence, graph |
| `EvidenceApiService` | FTS, detail, versions, lineage, presigned download, upload, verify |
| `DisputeApiService` | list, get, create (idempotent per transaction), legal transitions |
| `CaseApiService` | queue, detail, X-Ray, package manifest, the three decisions |
| `CaseDecision` | enum: legal source statuses, target status, emitted event, per decision |
| `CaseSignalGateway` | Temporal signal over HTTP; **not** transactional, so the remote call never holds a DB connection |
| `CaseTransitionWriter` | the transactional local write: status, approval columns, CASE event, audit entry |
| `InvestigationQueryService` | investigation row plus parsed result/verdict and findings |
| `PolicyApiService` | list, get, history, requirements, publish a new immutable version |
| `AuditQueryService` | audit search and chain verification |
| `GapQueryService` | the at-risk feed, severity-ordered |
| `FunnelQueryService` | the funnel aggregate with a default 7-day window |
| `AiToolsService` | the ten read-only lookups; nested `EntityOwnerResolver` port |
| `JpaEntityOwnerResolver` | order/shipment/refund id to owning transaction id |
| `ReadinessProbeService` | the dependency probes behind `/health/ready` |

### `api.stream`

| File | Notes |
|---|---|
| `FrameType` | the seven contract frame types plus `forEvent(EventType)`, the 28-to-7 fold |
| `StreamFrame` | the exact `{type, at, merchantId, data}` envelope; `from(CanonicalEvent)` |
| `StreamHub` | all subscriber registries, merchant-scoped fan-out, heartbeat, failure isolation |
| `ControlTowerWebSocketHandler` | `/ws/control-tower?merchantId=`; wraps sessions for concurrency |
| `WebSocketConfig` | registers the handler, mirrors the CORS origins |
| `EventStreamController` | the two SSE routes |
| `ControlTowerEventListener` | the Kafka consumer; idempotent, never fails a record |
| `StreamEventDeduplicator` | Redis `SETNX` on `pdei:idem:{eventId}:{group}`, bounded local fallback |
| `StreamSchedulingConfig` | registers the heartbeat from the typed `Duration` property |

### `api.support`

| File | Notes |
|---|---|
| `CorrelationIds` | MDC-backed accessor for the current correlation id |
| `Paging` | validates and clamps `page` / `size` into a `Pageable` |
| `Rows` | native aggregate `Object[]` rows to typed enum-keyed count maps |

### Tests

| File | Covers |
|---|---|
| `ApiTestFixtures` | fixed-instant sample domain objects |
| `controller/TransactionControllerTest` | page envelope, minor-unit money, filter pass-through, 404, timeline, readiness, recompute is POST-only, bad enum is 400 |
| `controller/EvidenceControllerTest` | search filters, 302 download, verify reports mismatch as 200, integrity 422, 404, multipart 201, invalid and missing metadata are 400 |
| `controller/CaseControllerTest` | queue, X-Ray payload, approve, actor required, reject note required, illegal transition 409, package 404, correlation id present |
| `controller/aitools/AiToolsControllerTest` | **no mutating mapping exists (reflection)**, mutating requests are unrouted, 401 without/with wrong token, blank token refuses, six of the lookups, literal-beats-template routing |
| `stream/StreamFrameTest` | exact envelope shape, heartbeat null handling, the event-type fold, payload identifier lifting |

---

## 4. Route table (contract section 8.1)

Base `http://localhost:8080/api/v1`. `page` is zero-based; `size` defaults to 25 and is clamped to
`pdei.api.paging.max-size` (200). Every list response is `PageResponse<T>`.

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| GET | `/health/ready` | - | `HealthResponse` | 200 READY/DEGRADED, 503 NOT_READY. Only Postgres is fatal |
| GET | `/merchants` | `page`, `size` | `PageResponse<MerchantResponse>` | sorted by id |
| GET | `/merchants/{merchantId}` | - | `MerchantResponse` | 404 when unknown |
| GET | `/merchants/{merchantId}/summary` | - | `MerchantSummaryResponse` | control-tower KPIs; counts only, no money aggregate |
| GET | `/transactions` | `merchantId`, `band`, `from`, `to`, `page`, `size` | `PageResponse<TransactionResponse>` | window is half-open `[from, to)`; sorted by `occurredAt` desc |
| GET | `/transactions/{transactionId}` | - | `TransactionDetailResponse` | row + `TransactionFacts` + readiness + evidence summary |
| GET | `/transactions/{transactionId}/timeline` | - | `TimelineResponse` | ordered by when things happened, not when observed |
| GET | `/transactions/{transactionId}/readiness` | `reasonCode?` | `ReadinessSnapshot` | stored snapshot when it matches the reason code, else computed |
| POST | `/transactions/{transactionId}/readiness/recompute` | `reasonCode?` | `ReadinessSnapshot` | recomputes **and persists**; idempotent; publishes no event |
| GET | `/transactions/{transactionId}/evidence` | - | `List<EvidenceView>` | |
| GET | `/transactions/{transactionId}/graph` | - | `EvidenceGraph` | nodes + edges incl. `CONTRADICTS` |
| GET | `/evidence` | `merchantId`, `type`, `status`, `q`, `transactionId`, `page`, `size` | `PageResponse<EvidenceView>` | `q` triggers Postgres FTS |
| GET | `/evidence/{evidenceId}` | - | `EvidenceView` | |
| GET | `/evidence/{evidenceId}/versions` | - | `EvidenceVersionsResponse` | row chain + stored-object ledger |
| GET | `/evidence/{evidenceId}/lineage` | - | `EvidenceGraph` | version chain + provenance |
| GET | `/evidence/{evidenceId}/download` | - | 302 `Location` | presigned MinIO URL; bytes are never proxied |
| POST | `/evidence` | multipart: `file` + JSON `metadata` (`EvidenceUploadRequest`) | 201 `EvidenceView` + `Location` | idempotent by content; sha256 computed from stored bytes |
| POST | `/evidence/{evidenceId}/verify` | - | `IntegrityReport` | 200 even when `intact: false`; a mismatch invalidates the artifact |
| GET | `/disputes` | `merchantId`, `status`, `reasonCode`, `page`, `size` | `PageResponse<DisputeView>` | |
| GET | `/disputes/{disputeId}` | - | `DisputeView` | |
| POST | `/disputes` | `CreateDisputeRequest` | 201 `DisputeView` + `Location` | idempotent per open transaction; `amountMinor` + `currency` |
| GET | `/disputes/{disputeId}/transitions` | - | `List<DisputeStatus>` | **additive**: legal next statuses for the UI |
| GET | `/cases` | `merchantId`, `status`, `page`, `size` | `PageResponse<CaseView>` | queue swimlanes |
| GET | `/cases/{caseId}` | - | `CaseView` | |
| GET | `/cases/{caseId}/xray` | - | `CaseXRay` | heaviest read in the API; computed fresh |
| POST | `/cases/{caseId}/approve` | `CaseDecisionRequest` | `CaseDecisionResponse` | `AWAITING_APPROVAL`/`INVESTIGATING` → `PREPARED`, emits `CasePrepared` |
| POST | `/cases/{caseId}/reject` | `CaseDecisionRequest` (note required) | `CaseDecisionResponse` | → `AWAITING_EVIDENCE`, emits `CaseEscalated` |
| POST | `/cases/{caseId}/submit` | `CaseDecisionRequest` | `CaseDecisionResponse` | `PREPARED`/`AWAITING_APPROVAL` → `SUBMITTED`, emits `CaseSubmitted` |
| GET | `/cases/{caseId}/package` | - | `PackageManifest` | 404 until assembled; a GET never assembles |
| GET | `/investigations/{investigationId}` | - | `InvestigationResponse` | proposal + verdict + findings |
| GET | `/policies` | `merchantId` (required) | `List<PolicyView>` | |
| GET | `/policies/{policyId}` | - | `PolicyView` | version currently in force |
| GET | `/policies/{policyId}/history` | - | `List<PolicyView>` | **additive**: immutable version history |
| GET | `/policies/{policyId}/requirements` | - | `RequirementsResponse` | |
| PUT | `/policies/{policyId}` | `PolicyUpsertRequest` | `PolicyView` | appends a version, closes the previous interval |
| GET | `/requirements` | `reasonCode`, `merchantId?` | `RequirementsResponse` | merchant-scoped or the seeded default matrix |
| GET | `/audit` | `entityType`+`entityId`, or `merchantId` (+`actor`, `from`, `to`), `page`, `size` | `PageResponse<AuditEvent>` | entity filters must be supplied together |
| GET | `/audit/verify-chain` | `merchantId` | `ChainVerification` | a broken chain is 200 with `intact: false` |
| GET | `/gaps` | `merchantId` (required), `type`, `severity`, `page`, `size` | `PageResponse<ReadinessGap>` | at-risk feed, severity desc |
| GET | `/gaps/transaction/{transactionId}` | - | `List<ReadinessGap>` | **additive**: feed drill-down |
| GET | `/metrics/funnel` | `merchantId?`, `from?`, `to?` | `FunnelResponse` | default window 7 days |

### Streaming

| Protocol | Path | Notes |
|---|---|---|
| WS | `/ws/control-tower?merchantId=` | server→client only; inbound text is answered with a HEARTBEAT and otherwise ignored |
| SSE | `/api/v1/stream/events?merchantId=` | canonical event tail; SSE event name `frame` |
| SSE | `/api/v1/stream/cases/{caseId}` | case progress; receives frames whose `data.caseId` matches |

Frame envelope, exactly as contract section 8.1:

```json
{ "type": "READINESS_UPDATED", "at": "2026-08-26T10:15:30.123Z", "merchantId": "MER-0001", "data": {} }
```

`type` ∈ `READINESS_UPDATED | EVIDENCE_ADDED | DISPUTE_CREATED | CASE_UPDATED | GAP_DETECTED |
CHAOS_INJECTED | HEARTBEAT`.

### AI tool surface (contract section 8.6) - GET only, `X-PDEI-Service-Token` required

| Method | Path | Response |
|---|---|---|
| GET | `/api/v1/ai-tools/transaction/{id}` | `TransactionFacts` |
| GET | `/api/v1/ai-tools/order/{id}` | `TransactionFacts.OrderFact` |
| GET | `/api/v1/ai-tools/shipment/{id}` | `TransactionFacts.ShipmentFact` |
| GET | `/api/v1/ai-tools/refund/{id}` | `TransactionFacts.RefundFact` |
| GET | `/api/v1/ai-tools/evidence/{id}` | `EvidenceView` |
| GET | `/api/v1/ai-tools/evidence/related?transactionId=` | `RelatedEvidenceResponse` |
| GET | `/api/v1/ai-tools/contradictions?transactionId=` | `ContradictionsResponse` |
| GET | `/api/v1/ai-tools/policy/applicable?merchantId=&reasonCode=` | `PolicyView` |
| GET | `/api/v1/ai-tools/requirements?reasonCode=` | `RequirementsResponse` |
| GET | `/api/v1/ai-tools/timeline/{transactionId}` | `TimelineResponse` |

### Ops

`/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics`,
`/actuator/loggers`, `/v3/api-docs`, `/swagger-ui.html`.

---

## 5. Error mapping

Every failure is rendered as `com.laserpay.pdei.common.error.ErrorResponse`:
`{code, message, correlationId, at, details}`.

| Exception | Status | Code |
|---|---|---|
| `NotFoundException` | 404 | `NOT_FOUND` |
| `ValidationException` | 400 | `VALIDATION_ERROR` |
| `PolicyViolationException` | **409** | `POLICY_VIOLATION` |
| `ConflictException` | 409 | `CONFLICT` |
| `EvidenceIntegrityException` | **422** | `EVIDENCE_INTEGRITY` |
| `UpstreamUnavailableException` | 503 | `UPSTREAM_UNAVAILABLE` |
| `UnknownEventTypeException` | 400 | `UNKNOWN_EVENT_TYPE` |
| bean validation / bad param / unreadable body | 400 | `VALIDATION_ERROR` |
| unsupported method | 405 | `METHOD_NOT_ALLOWED` |
| no such route | 404 | `NOT_FOUND` |
| upload too large | 413 | `PAYLOAD_TOO_LARGE` |
| rate limited (filter) | 429 | `RATE_LIMITED` |
| missing/invalid service token (filter) | 401 | `UNAUTHORIZED` |
| anything else | 500 | `INTERNAL_ERROR` (generic message; the correlation id is the way in) |

> **Deliberate divergence, recorded in section 10.** `PdeiException.httpStatus()` in platform-common
> carries 422 for `PolicyViolationException` and 409 for `EvidenceIntegrityException` - the reverse
> of the two bolded rows. The gateway's HTTP surface is specified by the table above, so
> `GlobalExceptionHandler` maps explicitly per exception type and does **not** read `httpStatus()`.

---

## 6. Inbound contracts (what this module consumes)

**evidence-core beans** (via `CoreAutoConfiguration` / `CorePersistenceAutoConfiguration`):
`EvidenceService`, `EvidenceIntegrityService`, `EvidenceGraphService`, `EvidenceLineageService`,
`EvidenceSearchService`, `ReadinessEngine`, `ContradictionDetector`, `PolicyEngine`,
`PolicyVersionService`, `DisputeService`, `CaseAssemblyService`, `TimelineService`, `AuditRecorder`,
`ObjectStore`, `Clocks`, and the SPI ports `TransactionRepositoryPort`, `EvidenceRepositoryPort`,
`ReadinessRepositoryPort`, `CaseRepositoryPort`, `AuditRepositoryPort`, `EventPublisherPort`.

**platform-persistence repositories** (only where no port covers the need):
`MerchantRepository`, `TransactionRepository`, `DisputeRepository`, `DisputeCaseRepository`,
`EvidenceRepository`, `ReadinessGapRepository`, `InvestigationFindingRepository`,
`OrderRepository`, `ShipmentRepository`, `RefundRepository`.

**Kafka topics consumed** (group `pdei-api-gateway-service`, one listener id `pdei-control-tower`):
`pdei.readiness.events.v1`, `pdei.case.events.v1`, `pdei.evidence.events.v1`,
`pdei.dispute.events.v1`. Payload `CanonicalEvent` as JSON text.

**Redis keys read/written:** `pdei:ratelimit:{merchantId}:{window}`,
`pdei:idem:{eventId}:pdei-api-gateway-service`.

**HTTP called out:** `POST {orchestrator}/orchestrator/v1/cases/{caseId}/signals/humanDecision`
(assumed shape - see known gaps).

**Tables reached** (through the ports and repositories, never with hand-written SQL in this module):
`merchants`, `transactions`, `payments`, `orders`, `order_lines`, `shipments`, `deliveries`,
`refunds`, `communications`, `evidence`, `evidence_versions`, `evidence_relationships`, `policies`,
`policy_versions`, `evidence_requirements`, `disputes`, `dispute_cases`, `case_evidence`,
`readiness_snapshots`, `readiness_gaps`, `investigations`, `investigation_findings`,
`ai_admission_log`, `audit_events`.

---

## 7. Outbound contracts (what this module produces)

| Output | Detail |
|---|---|
| REST | Everything in section 4 above |
| WebSocket / SSE | `StreamFrame` envelopes, merchant-scoped |
| Kafka | `pdei.case.events.v1`: `CasePrepared`, `CaseEscalated`, `CaseSubmitted` from human decisions, partition key `merchantId + ":" + aggregateId` |
| Kafka (indirect) | `pdei.audit.events.v1` via `AuditRecorder` for each human case decision |
| Postgres writes | `readiness_snapshots` (recompute), `evidence` + `evidence_versions` (upload, via core), `dispute_cases` status and approval columns, `disputes` (create, via core), `policy_versions` (publish, via core), `audit_events` (append-only) |
| MinIO | evidence objects written through `EvidenceService`; presigned GET URLs handed to the browser |
| Metrics | `pdei_events_processed_total{service,type,outcome}`, `pdei_events_duplicate_total{service}`, plus everything evidence-core and Spring register |

---

## 8. Configuration and environment variables

Contract section 15 names on the left, the property they bind to on the right.

| Env var | Property | Default |
|---|---|---|
| `PDEI_POSTGRES_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/pdei` |
| `PDEI_POSTGRES_USER` | `spring.datasource.username` | `pdei` |
| `PDEI_POSTGRES_PASSWORD` | `spring.datasource.password` | `pdei` |
| `PDEI_KAFKA_BOOTSTRAP` | `spring.kafka.bootstrap-servers` | `localhost:29092` |
| `PDEI_REDIS_URL` | `spring.data.redis.url` | `redis://localhost:6379` |
| `PDEI_MINIO_ENDPOINT` | `pdei.core.storage.endpoint` | `http://localhost:9000` |
| `PDEI_MINIO_ACCESS_KEY` | `pdei.core.storage.access-key` | `pdei-minio` |
| `PDEI_MINIO_SECRET_KEY` | `pdei.core.storage.secret-key` | `pdei-minio-secret` |
| `PDEI_AI_SERVICE_URL` | `pdei.core.ai.service-url` | `http://localhost:8000` |
| `PDEI_SERVICE_TOKEN` | `pdei.api.service-token` **and** `pdei.core.ai.service-token` | `dev-service-token` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `otel.exporter.otlp.endpoint` | `http://localhost:4318` |
| `OTEL_SERVICE_NAME` | `otel.service.name` | `api-gateway-service` |

Module-specific switches (all optional):

| Env var | Property | Default | Effect |
|---|---|---|---|
| `PDEI_FRONTEND_ORIGIN` | `pdei.api.cors.allowed-origins[0]` | `http://localhost:3000` | CORS **and** the WebSocket handshake origin |
| `PDEI_RATE_LIMIT_ENABLED` | `pdei.api.rate-limit.enabled` | `true` | |
| `PDEI_STREAM_KAFKA_ENABLED` | `pdei.api.stream.kafka-enabled` | `true` | `false` runs the gateway with no live frames |
| `PDEI_ORCHESTRATOR_ENABLED` | `pdei.api.orchestrator.enabled` | `true` | `false` always takes the local transition |
| `PDEI_ORCHESTRATOR_URL` | `pdei.api.orchestrator.base-url` | `http://localhost:8085` | |
| `PDEI_FLYWAY_ENABLED` | `spring.flyway.enabled` | `true` | set `false` when a migration job runs first |

Other tunables under `pdei.api`: `rate-limit.requests-per-window` (600), `rate-limit.window` (1m),
`stream.heartbeat-interval` (15s), `stream.sse-timeout` (30m), `stream.max-sessions-per-merchant`
(50), `stream.dedupe-ttl` (7d), `paging.default-size` (25), `paging.max-size` (200).

---

## 9. Dependencies on other modules

| Dependency | Why |
|---|---|
| `platform-common` | **hard.** `Money`, `Ids`, `CanonicalEvent`, `AuditEvent`, every domain enum, `Topics`, `ConsumerGroups`, `MetricNames`, `Json`, `Clocks`, the sealed `PdeiException` hierarchy and `ErrorResponse`. Every one of these names is fixed by `docs/SHARED-LIBRARY-API.md` |
| `platform-persistence` | entities and Spring Data repositories for the reads no SPI port covers, plus `DataSource`/JPA/Flyway autoconfiguration |
| `evidence-core` | the entire domain engine; this module is a caller and nothing more |
| `case-orchestrator-service` | **runtime, optional.** Target of the human-decision signal; unreachable means the local fallback runs |
| `ai-reasoning-service` | **runtime, inbound.** Calls the `/ai-tools` surface with the service token |
| `frontend` | consumes every route in section 4 and the WebSocket/SSE frames |

Behaviour when an infrastructure dependency is missing:

- **Redis down** → rate limiting fails open, stream dedupe falls back to a bounded in-memory set.
  Every route still works. `/health/ready` reports DEGRADED.
- **Kafka down** → no live frames; REST is unaffected. `missing-topics-fatal: false` means startup
  still succeeds.
- **MinIO down** → upload and download fail; nothing else does.
- **Postgres down** → NOT_READY and 503 from `/health/ready`. This is the only fatal dependency.

---

## 10. Known gaps and TODOs

1. **Exception status divergence from `platform-common`.** `PdeiException.httpStatus()` says
   `PolicyViolationException` = 422 and `EvidenceIntegrityException` = 409; this module maps them
   409 and 422 respectively, per its own specification. The mapping is explicit and per type in
   `GlobalExceptionHandler`. If the platform ever reconciles the two, that class is the single place
   to change, and the `PdeiException` catch-all already defers to `httpStatus()`.
2. **Redis dedupe key is group-suffixed.** Contract section 12 writes the key as
   `pdei:idem:{eventId}`; this module uses `pdei:idem:{eventId}:pdei-api-gateway-service`. The bare
   key is shared platform-wide, so the first consumer to claim an event would make every other
   consumer skip it. The Postgres half of the same primitive is already keyed on
   `(event_id, consumer_group)`; this mirrors it. Worth promoting to the contract.
3. **The gateway does not write `processed_events`.** Deliberate: this consumer only pushes a frame
   at a browser, and a duplicated frame is invisible. If the fan-out ever gains a side effect, the
   durable ledger must be added.
4. **Frame types collapse the event enum.** All EVIDENCE events become `EVIDENCE_ADDED`, all DISPUTE
   events `DISPUTE_CREATED`, all CASE events `CASE_UPDATED`. The real `eventType` is in
   `data.eventType`. Widening the union would be a contract change.
5. **`CHAOS_INJECTED` has no producer yet.** The frame type exists and `StreamHub.broadcast` accepts
   it, but no topic the gateway consumes carries chaos events. simulator-service will need to publish
   them, or the gateway will need a fifth subscription.
6. **The orchestrator signal endpoint is an assumption.** `POST
   /orchestrator/v1/cases/{caseId}/signals/humanDecision` is not in contract section 8;
   case-orchestrator-service does not exist yet. The deterministic fallback
   (`CaseTransitionWriter.apply`) is fully implemented and the response says which path ran. When the
   orchestrator lands, confirm the path and payload and delete this note.
7. **`REJECT` maps to `AWAITING_EVIDENCE` and emits `CaseEscalated`.** `CaseStatus` has no REJECTED
   constant and inventing one would break the shared enum. Revisit if the enum gains one.
8. **No aggregated money in the merchant summary.** Summing `amount_minor` across disputes needs a
   currency-aware aggregate no repository exposes, and adding queries to platform-persistence is out
   of this module's scope. A single number mixing currencies would be worse than none.
9. **`averageReadinessScore` is a band-weighted approximation**, not the true mean, for the same
   reason (no `SUM(readiness_score)` aggregate). Each band contributes its midpoint. Null when the
   merchant has no scored transactions. Relatedly, `transactions` in the summary is the sum of the
   band distribution, so a transaction whose readiness has never been computed (null
   `readiness_band`) is not counted. That is the intended reading of "transactions the control tower
   can say something about", but it is not the row count of the table.
10. **List routes backed by SPI ports report an approximate total.** `disputes`, `cases` and `gaps`
    come from ports that return a bounded `List` with no count query, so `PageResponse.ofSlice`
    reports "at least this many". Routes backed by Spring Data `Page` (`merchants`, `transactions`,
    `evidence`) report exact totals.
11. **Logs are pattern-formatted, not JSON.** Contract section 13 asks for structured JSON to Loki.
    The pattern carries `traceId`, `spanId`, `correlationId` and `merchantId`, which promtail can
    parse, but a proper encoder (or Boot 3.4's built-in structured logging) is the real fix.
12. **No authentication on the merchant routes.** Only the AI callback surface is authenticated. A
    real deployment needs merchant identity and tenant isolation on `/api/v1/**`; today `merchantId`
    is a query parameter the caller asserts. The rate limiter keys off the same untrusted value.
13. **No integration test with a live context.** Testcontainers is on the test classpath and unused;
    the suite is MockMvc slices and pure unit tests. A `@SpringBootTest` against Postgres + Redis +
    Kafka containers, exercising the Kafka-to-WebSocket path end to end, is the obvious next test.
14. **`ReadinessProbeService` reports Kafka from container state**, not from a broker round trip.
    That is the more useful signal for the control tower but it will read UP briefly while a
    container is retrying a dead broker.

---

## 11. How to build and run

```bash
cd backend
mvn -q -pl api-gateway-service -am clean install   # build this module and its three dependencies
mvn -q -pl api-gateway-service test                # the MockMvc slices and unit tests
mvn -q -pl api-gateway-service spring-boot:run     # run against a local docker-compose stack
```

Runs on <http://localhost:8080>. Swagger UI at <http://localhost:8080/swagger-ui.html>.

Minimum infrastructure for a full run: Postgres (5432) is required; Redis (6379), Kafka (29092) and
MinIO (9000) are each optional and degrade as described in section 9.

Docker, from the `backend` directory (the build context must be the reactor root):

```bash
docker build -f api-gateway-service/Dockerfile -t pdei/api-gateway-service:dev .
docker run --rm -p 8080:8080 --network pdei-net \
  -e PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
  -e PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
  -e PDEI_REDIS_URL=redis://redis:6379 \
  -e PDEI_MINIO_ENDPOINT=http://minio:9000 \
  -e PDEI_SERVICE_TOKEN=dev-service-token \
  pdei/api-gateway-service:dev
```

Quick manual checks:

```bash
curl localhost:8080/api/v1/health/ready
curl "localhost:8080/api/v1/transactions?merchantId=MER-0001&size=5"
curl -H "X-PDEI-Service-Token: dev-service-token" \
     localhost:8080/api/v1/ai-tools/requirements?reasonCode=GOODS_NOT_RECEIVED
curl -i localhost:8080/api/v1/ai-tools/requirements?reasonCode=GOODS_NOT_RECEIVED   # expect 401
```

---

## 12. Extension points

- **Add a route.** Put the orchestration in the matching `api.service` class, the shape in
  `api.dto`, the mapping in the controller, and a row in the section 4 table. Never reach into a
  repository from a controller.
- **Add a frame type.** Extend `FrameType`, map it in `FrameType.forEvent`, and update the
  frontend's TypeScript union. Remember the contract fixes this set: widening it is a contract
  change, not a local one.
- **Add a stream source.** Add the topic to `ControlTowerEventListener`'s `topics` array. Dedupe,
  metrics and fan-out are already generic.
- **Change the AI tool surface.** Add a `@GetMapping` to `AiToolsController` and a lookup to
  `AiToolsService`. `AiToolsControllerTest.noMutatingMappingsExist` will refuse anything else.
- **Swap the rate limiter for a token bucket.** Replace the body of `RateLimitFilter.increment` with
  a Lua script; `RedisAiBudgetGate` in evidence-core is the pattern to copy.
- **Real merchant authentication.** Add a filter ahead of `RateLimitFilter` that resolves the
  merchant from a credential and rejects any request whose `merchantId` parameter disagrees. Every
  service method already takes `merchantId` explicitly, so nothing below the controller changes.
