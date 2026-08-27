# `backend/ingestion-service` — PDEI Event Intake (port 8081)

> Module context. Normative references, in precedence order:
> `docs/PLATFORM-CONTRACT.md` → `docs/SHARED-LIBRARY-API.md` → `docs/event-catalog.md` →
> `planner/pre-dispute-evidence-intelligence-reference.md`.
> If this file and those disagree, **those win and this file is stale**.

| Item | Value |
|---|---|
| Maven artifactId | `ingestion-service` |
| Java package root | `com.laserpay.pdei.ingestion` |
| Kind | Spring Boot web application |
| Host/container port | 8081 |
| REST base | `http://localhost:8081/ingest/v1` |
| Health | `/actuator/health` · Metrics `/actuator/prometheus` |
| Produces to | `pdei.raw.events.v1`, `pdei.dlq.v1` |
| Consumes from | *nothing* — this service has no Kafka consumer |
| Consumer group (idempotency ledger only) | `pdei-ingestion-service` |

---

## 1. Purpose

**The only write door into the platform for events that originate outside it.**

Everything the evidence graph is later built from enters here. The service does exactly three
things to every submitted event, in this order:

1. **Validate** it against a registered JSON Schema, and say precisely which field is wrong when it
   is not valid.
2. **Deduplicate** it, so that a retried webhook, a replayed adapter batch or a network-doubled POST
   produces one fact, not two.
3. **Publish** it to `pdei.raw.events.v1` keyed `merchantId + ":" + aggregateId`, with the
   contract's Kafka headers, dead-lettering to `pdei.dlq.v1` rather than dropping anything.

The only other producer to `pdei.raw.events.v1` is `simulator-service`, which is a synthetic source
of the same shape.

### What this service deliberately does NOT do

- **It does not interpret the body.** Source vocabulary (`payment_intent.succeeded`) is translated
  into the canonical event model by `normalization-worker` and nowhere else. Ingestion only proves
  the payload is well formed enough to be worth replaying, and that it has not been seen before.
  The body is preserved **verbatim**, because a normalisation bug must be fixable by replaying the
  raw topic rather than by asking four source systems to re-send last quarter.
- **It does not write to the domain tables.** The only table it touches is
  `pdei.processed_events`, and only as the durable half of the idempotency claim.
- **It contains no AI code, and never will** (PLATFORM-CONTRACT §17 rules 2 and 14).

---

## 2. Responsibilities

| # | Responsibility | Where |
|---|---|---|
| 1 | Serve the five routes of PLATFORM-CONTRACT §8.2 | `controller/` |
| 2 | Authenticate per-source webhooks by HMAC | `security/WebhookSignatureVerifier` |
| 3 | Load and index the JSON Schemas | `validation/SchemaRegistry` |
| 4 | Validate submissions, with field-level errors | `validation/RawEventValidator` |
| 5 | Claim event identity exactly once | `dedupe/IdempotencyService` |
| 6 | Publish to Kafka durably, or dead-letter | `publisher/RawEventPublisher` |
| 7 | Emit the contract's ingestion metrics | `metrics/IngestionMetrics` |
| 8 | Own the shared JSON Schema files | `/schemas/events/*.schema.json` (repo root) |

---

## 3. File-by-file map

```
backend/ingestion-service/
├── pom.xml                       deps + the <resources> copy of /schemas/events into the jar
├── Dockerfile                    multi-stage; build context is the REPOSITORY ROOT
├── context.md                    this file
└── src/
    ├── main/java/com/laserpay/pdei/ingestion/
    │   ├── IngestionServiceApplication.java   @SpringBootApplication + @ConfigurationPropertiesScan
    │   ├── config/
    │   │   ├── IngestionProperties.java       all `ingestion.*` tuning, @Validated
    │   │   ├── IngestionConfiguration.java    Clocks bean + Jackson alignment with common.json.Json
    │   │   └── KafkaProducerConfig.java       acks=all + idempotent producer, JsonSerializer,
    │   │                                      NewTopic declarations for RAW_EVENTS and DLQ
    │   ├── controller/
    │   │   ├── IngestionController.java       POST /events, POST /events/batch, GET /schemas, GET /stats
    │   │   ├── WebhookController.java         POST /events/{sourceSystem}/webhook (raw bytes)
    │   │   └── IngestionExceptionHandler.java maps failures onto common.error.ErrorResponse
    │   ├── dedupe/
    │   │   └── IdempotencyService.java        Redis SETNX -> processed_events -> fail-open
    │   ├── metrics/
    │   │   └── IngestionMetrics.java          contract §13 meters + the /stats counters
    │   ├── model/
    │   │   ├── IngestRequest.java             submission DTO (mirrors raw-event.schema.json)
    │   │   ├── IngestResponse.java            {accepted, rejected[], duplicates} — exactly §8.2
    │   │   ├── RejectedEvent.java             one rejection, with a stable machine-readable code
    │   │   ├── FieldError.java                {field, message, code, schemaPath}
    │   │   ├── IngestBatchResult.java         internal: response + assigned ids
    │   │   ├── SchemaDescriptor.java          GET /schemas row
    │   │   └── IngestionStats.java            GET /stats body
    │   ├── publisher/
    │   │   └── RawEventPublisher.java         partition key, headers, synchronous send, DLQ
    │   ├── security/
    │   │   ├── WebhookSignatureVerifier.java  HMAC-SHA256, constant-time, replay window
    │   │   └── WebhookSignatureException.java -> 401, deliberately uninformative
    │   ├── service/
    │   │   ├── IngestionService.java          the pipeline; identity derivation; header assembly
    │   │   └── AggregateIdResolver.java       finds the aggregate id for the partition key
    │   └── validation/
    │       ├── SchemaRegistry.java            classpath + filesystem loading, key normalisation
    │       ├── RegisteredSchema.java          compiled schema + x-pdei-* metadata
    │       ├── RawEventValidator.java         structural + envelope + payload passes
    │       └── ValidationOutcome.java         {valid, errors[], schemaName, code}
    ├── main/resources/
    │   └── application.yml                    default / dev / test profiles
    └── test/java/com/laserpay/pdei/ingestion/
        ├── IngestionTestSupport.java          the @WebMvcTest bean set (real validator, fixed clock)
        ├── controller/IngestionControllerTest.java   accept, reject, duplicate, batch cap, stats
        ├── controller/WebhookControllerTest.java     signed / unsigned / stale / unknown source
        ├── dedupe/IdempotencyServiceTest.java        Redis -> Postgres -> fail-open
        ├── publisher/RawEventPublisherTest.java      key, headers, dead-letter on failure
        ├── security/WebhookSignatureVerifierTest.java header shapes, tamper, replay
        └── validation/SchemaRegistryTest.java        loading, aliases, EventType drift check
```

### Shared assets owned by this module

`/schemas/events/` at the repository root — **32 files**, created here and consumed by
normalization-worker, simulator-service, the Python service and the frontend:

- `canonical-event.schema.json` — the PLATFORM-CONTRACT §3 envelope (strict:
  `additionalProperties: false`);
- `raw-event.schema.json` — the ingestion submission **and** the `pdei.raw.events.v1` payload
  (permissive: `additionalProperties: true`);
- 30 payload schemas, one per `EventType`, named in kebab-case:
  `payment-created`, `payment-authorized`, `payment-captured`, `payment-failed`,
  `order-created`, `order-fulfilled`, `order-cancelled`,
  `shipment-created`, `shipment-dispatched`, `shipment-delivered`,
  `refund-created`, `refund-processed`,
  `communication-created`, `communication-received`,
  `evidence-added`, `evidence-expired`, `evidence-invalidated`,
  `dispute-created`, `dispute-updated`, `dispute-closed`,
  `readiness-recomputed`, `readiness-gap-detected`,
  `case-opened`, `case-evidence-attached`, `case-investigated`, `case-prepared`,
  `case-escalated`, `case-submitted`, `case-closed`, `audit-recorded`.

Schema conventions, all deliberate:

- **Draft 2020-12**, `$id` = `https://schemas.pdei.laserpay.com/events/<name>.schema.json`.
- **Self-contained.** Every `$ref` points inside the file's own `$defs`. No cross-file references
  means no URI resolver, no network fetch, no load-order dependency: a schema either compiles alone
  or it is broken. The cost is that `money` is repeated in every file; the benefit is that the
  registry is thirty lines instead of a resolver.
- **`x-pdei-event-type` / `x-pdei-aggregate-type` / `x-pdei-origin`** annotate each file so it is
  self-describing and no separate manifest can drift from it. JSON Schema ignores unknown keywords.
- **`additionalProperties: true` on payloads.** A newer source adding a field must not be rejected.
- **Money is always `{amountMinor: integer, currency: "^[A-Z]{3}$"}`** with
  `additionalProperties: false`, so a bare decimal fails validation at the front door.
- `SchemaRegistryTest.everyEventTypeHasASchema` fails the build if `EventType` and this directory
  ever diverge.

---

## 4. Inbound contracts

### 4.1 REST (PLATFORM-CONTRACT §8.2), base `/ingest/v1`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/events` | one raw event; header `Idempotency-Key` |
| `POST` | `/events/batch` | JSON array, max 1000 |
| `POST` | `/events/{sourceSystem}/webhook` | HMAC-signed, raw bytes |
| `GET`  | `/schemas` | registered source schemas |
| `GET`  | `/stats` | accepted / rejected / deduped counters |

**Response.** `202 Accepted` with exactly `{ "accepted": n, "rejected": [...], "duplicates": n }`.
That status describes the *request*, which was well formed and has been processed; individual
events that failed are reported inside the body. `accepted + duplicates + rejected.length` always
equals the number of submitted events. The id assigned to a single accepted event comes back in the
`X-PDEI-Raw-Event-Id` response header rather than by widening the body contract.

A 4xx/5xx means the *request* was wrong: unparseable JSON or a batch over the cap → `400`
(`VALIDATION_ERROR`); a webhook that fails signature verification → `401`
(`WEBHOOK_SIGNATURE_INVALID`). All error bodies are `com.laserpay.pdei.common.error.ErrorResponse`.

**Rejection codes** (`RejectedEvent`): `SCHEMA_VALIDATION_FAILED`, `UNKNOWN_SCHEMA`,
`PUBLISH_FAILED`, `MALFORMED_REQUEST`.

Submission body ≡ `schemas/events/raw-event.schema.json`. Required: `sourceSystem`,
`sourceEventType`, `merchantId`, and `body` (alias `payload`). Optional: `rawEventId` (alias
`eventId`), `aggregateId`, `correlationId`, `causationId`, `occurredAt`, `idempotencyKey`,
`headers`.

### 4.2 Redis

`SETNX pdei:idem:{key}` with a 7 day TTL, where `{key}` is the event's idempotency key — which
defaults to its event id, so the key is literally `pdei:idem:{eventId}` in the ordinary case
(PLATFORM-CONTRACT §12).

### 4.3 Postgres

`pdei.processed_events` only, through `ProcessedEventRepository.markProcessed(eventId,
consumerGroup)` with `consumerGroup = pdei-ingestion-service`. No other table is read or written.
`spring.flyway.enabled` defaults to **false** here: migrations are owned by `platform-persistence`
and should be applied by exactly one service per environment.

### 4.4 Kafka

None. This service has no consumer and no `@KafkaListener`.

---

## 5. Outbound contracts

### 5.1 `pdei.raw.events.v1` (12 partitions)

- **Value**: `com.laserpay.pdei.common.event.RawEventEnvelope`, serialised as JSON with type
  headers switched off (`JsonSerializer.noTypeInfo()`), so the consumer is not pinned to a Java
  class name.
- **Key**: `merchantId + ":" + aggregateId`. The aggregate id is resolved, in order, from the
  submission's explicit `aggregateId`, then the body's `aggregateId`, `paymentId`, `orderId`,
  `shipmentId`, `deliveryId`, `refundId`, `communicationId`, `evidenceId`, `disputeId`, `caseId`,
  `transactionId`, `customerId` (camelCase or snake_case), then a bare `id` last. If nothing is
  found, the key falls back to `RawEventEnvelope.partitionKey()` =
  `merchantId + ":" + idempotencyKey` — still merchant-scoped, still stable per fact.
- **Record headers** (`common.kafka.EventHeaders`): `pdei-event-id`, `pdei-event-type` (the
  *source* event type), `pdei-merchant-id`, `pdei-correlation-id`, `pdei-schema-version`,
  `pdei-attempt` (`1`), and `traceparent` when the caller supplied one.
- **Envelope `headers` map** — the free-form source metadata plus these PDEI routing hints, which
  `normalization-worker` needs to build a `CanonicalEvent` without re-deriving them from the body:

  | Key | Meaning |
  |---|---|
  | `pdei-source-system` | the `sourceSystem` path/field |
  | `pdei-merchant-id` | merchant |
  | `pdei-correlation-id` | correlation id (defaults to the raw event id) |
  | `pdei-received-at` | ISO-8601 instant PDEI accepted the event → `observedAt` |
  | `pdei-aggregate-id` | resolved aggregate id, when one was found |
  | `pdei-causation-id` | supplied causation id, when present |
  | `pdei-occurred-at` | source-side time → `occurredAt` |
  | `pdei-schema` | name of the schema that validated it, when one matched |
  | `traceparent` | W3C trace context, when present |

  Plus, for webhooks, an allowlist of transport headers (`content-type`, `user-agent`, the
  configured timestamp and event-type headers). The signature header is **never** copied onto the
  topic.

### 5.2 `pdei.dlq.v1` (6 partitions)

`DeadLetterEnvelope` on publish failure, keyed identically. `partition` and `offset` are `-1`
because a producer-side failure never reached a partition and has no offset.

### 5.3 Metrics (PLATFORM-CONTRACT §13)

| Metric | Tags | When |
|---|---|---|
| `pdei_events_ingested_total` | `source`, `type` | event published |
| `pdei_events_duplicate_total` | `service=ingestion-service` | idempotency suppressed it |
| `pdei_events_processed_total` | `service`, `type`, `outcome` | exactly one per event: `success`, `duplicate`, `failure`, `dead_lettered` |
| `pdei_event_processing_latency_seconds` | `service`, `type` | validation → broker ack |

Cardinality is bounded on purpose: `type` is the **resolved schema name** (or `unmapped`), never
the free-text source event type; `source` must look like an adapter identity
(`^[a-z0-9][a-z0-9-]{0,31}$`) or it folds to `other`.

---

## 6. Configuration

### 6.1 Environment variables (PLATFORM-CONTRACT §15 names)

| Variable | Default | Used for |
|---|---|---|
| `PDEI_KAFKA_BOOTSTRAP` | `localhost:29092` | producer |
| `PDEI_REDIS_URL` | `redis://localhost:6379` | idempotency fast path |
| `PDEI_POSTGRES_URL` / `_USER` / `_PASSWORD` | `…/pdei`, `pdei`, `pdei` | idempotency ledger |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | traces |
| `OTEL_SERVICE_NAME` | `ingestion-service` | traces |

Module-specific:

| Variable | Default | Effect |
|---|---|---|
| `PDEI_SCHEMAS_DIR` | `/schemas/events` | filesystem schema directory, overrides the bundled copy |
| `PDEI_INGEST_STRICT_SCHEMAS` | `false` | reject event types with no registered schema |
| `PDEI_WEBHOOK_VERIFY` | `true` | **must stay true outside local development** |
| `PDEI_WEBHOOK_SECRET_PSP_ADAPTER` / `_ORDER_SYSTEM` / `_LOGISTICS` / `_CRM` | dev values | per-source HMAC secrets |
| `PDEI_FLYWAY_ENABLED` | `false` | run migrations from this service (standalone use only) |
| `PDEI_LOG_LEVEL` | `INFO` | `com.laserpay.pdei` log level |

### 6.2 `ingestion.*` properties (`IngestionProperties`)

```
ingestion.batch.max-size                        1000        contract §8.2
ingestion.schemas.classpath-location            classpath*:schemas/events/*.schema.json
ingestion.schemas.directories                   [/schemas/events]
ingestion.schemas.validate-envelope             true
ingestion.schemas.fail-on-unknown-event-type    false
ingestion.schemas.aliases                       source vocabulary -> schema (bracket-quoted keys)
ingestion.dedupe.enabled                        true
ingestion.dedupe.key-prefix                     pdei:idem:                 contract §12
ingestion.dedupe.ttl                            7d
ingestion.dedupe.redis-enabled                  true
ingestion.dedupe.postgres-fallback-enabled      true
ingestion.dedupe.fail-open                      true
ingestion.publisher.send-timeout                10s
ingestion.publisher.dlq-enabled                 true
ingestion.publisher.create-topics               true
ingestion.webhook.signature-verification-enabled true
ingestion.webhook.signature-header              X-PDEI-Signature
ingestion.webhook.timestamp-header              X-PDEI-Timestamp
ingestion.webhook.event-type-header             X-PDEI-Event-Type
ingestion.webhook.merchant-header               X-PDEI-Merchant-Id
ingestion.webhook.algorithm                     HmacSHA256
ingestion.webhook.tolerance                     5m
ingestion.webhook.secrets.<sourceSystem>        shared secret
```

Alias map keys must be bracket-quoted in YAML (`"[payment_intent.succeeded]": PaymentCaptured`)
because Spring's relaxed binding otherwise reads a dotted key as a nested map.

### 6.3 Profiles

- **default** — signature verification on, everything pointed at localhost.
- **`dev`** — `ingestion.webhook.signature-verification-enabled=false` and DEBUG logging. The
  verifier logs a WARN on every unauthenticated call, on purpose.
- **`test`** — excludes the JPA/Redis/Flyway autoconfigurations and disables topic creation so a
  context starts with no infrastructure at all.

---

## 7. Dependencies on other modules

| Module | Used for |
|---|---|
| `platform-common` | `RawEventEnvelope`, `DeadLetterEnvelope`, `EventType`, `Topics`, `EventHeaders`, `ConsumerGroups`, `MetricNames`, `Json`, `Hashes`, `Clocks`, `ErrorResponse`, `PdeiException` and subclasses |
| `platform-persistence` | `ProcessedEventRepository`, `ProcessedEventId`, and `PersistenceAutoConfiguration` (transitively brings spring-boot-starter-data-jpa, Flyway, the Postgres driver) |

Nothing depends on `evidence-core`; ingestion has no domain logic. Downstream, only
`normalization-worker` consumes what this service produces.

---

## 8. Build and run

```bash
# from the repo root — the module POM copies /schemas/events into the jar, so build from here
mvn -f backend/pom.xml -pl ingestion-service -am -DskipTests package

# tests (MockMvc + unit; no broker, no Redis, no Postgres required)
mvn -f backend/pom.xml -pl ingestion-service test

# run against a local dev stack
mvn -f backend/ingestion-service/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev

# container — build context is the REPOSITORY ROOT
docker build -f backend/ingestion-service/Dockerfile -t pdei/ingestion-service:dev .
docker run --rm -p 8081:8081 --network pdei-net pdei/ingestion-service:dev
```

Smoke test:

```bash
curl -sS -X POST http://localhost:8081/ingest/v1/events \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: psp-evt-1001' \
  -d '{"sourceSystem":"psp-adapter","sourceEventType":"PaymentCaptured","merchantId":"MER-0001",
       "occurredAt":"2026-08-26T10:15:30.123Z",
       "body":{"paymentId":"PAY-000123","transactionId":"TX-000123",
               "capturedAmount":{"amountMinor":1299900,"currency":"INR"},
               "capturedAt":"2026-08-26T10:15:30.123Z"}}'
# {"accepted":1,"rejected":[],"duplicates":0}   -- repeat it: {"accepted":0,...,"duplicates":1}

curl -sS http://localhost:8081/ingest/v1/schemas | head
curl -sS http://localhost:8081/ingest/v1/stats
curl -sS http://localhost:8081/actuator/prometheus | grep pdei_events
```

---

## 9. Design decisions worth not re-litigating

1. **Per-event rejection, not all-or-nothing.** A batch of 500 with three bad events publishes 497.
   One adapter bug must not cost the other 497 facts.
2. **Synchronous Kafka send with `acks=all` and an idempotent producer.** A 202 that means "buffered
   somewhere" is a lie a payments platform cannot afford. `accepted` means the broker acknowledged
   the record.
3. **Dedupe claims the idempotency key, which defaults to the event id.** This satisfies
   `pdei:idem:{eventId}` exactly in the ordinary case while still honouring an `Idempotency-Key`
   header. On a batch the header is used as a *prefix* with the array index appended — otherwise one
   header would suppress 999 distinct events.
4. **A content SHA-256 is the last-resort idempotency key.** An adapter that supplies no identity at
   all still cannot double-book a fact by retrying.
5. **Keys longer than 64 characters are folded to their SHA-256 hex.**
   `processed_events.event_id` is `VARCHAR(64)`, and the two stores must agree on identity or the
   fallback is not a fallback.
6. **Fail-open when neither dedupe store is reachable.** Every consumer is idempotent by contract
   (rule 9), so an extra duplicate converges; a dropped `PaymentCaptured` never comes back. Flip
   `ingestion.dedupe.fail-open` where that trade is wrong.
7. **A failed publish releases the idempotency claim.** Otherwise the caller's retry of a fact that
   never reached Kafka would be reported as a duplicate and lost silently.
8. **Verify, then parse, then ingest.** The webhook controller takes `byte[]`, because
   re-serialising a parsed body changes key order and whitespace and invalidates every signature.
9. **Self-contained schemas.** See §3. A little duplication buys a registry with no URI resolver.
10. **Unknown event types are accepted by default.** Ingestion preserves facts; the source-to-canonical
    mapping and its dead-lettering belong to `normalization-worker`.

---

## 10. Known gaps and TODOs

1. **`format` keywords are annotations, not assertions.** Draft 2020-12 makes `format` non-asserting
   by default and the registry does not enable format assertions, so a malformed `date-time` string
   passes schema validation (it is still caught downstream when `normalization-worker` parses it to
   an `Instant`). Enabling `SchemaValidatorsConfig.formatAssertionsEnabled` is a one-line change
   whose networknt API surface should be pinned first.
2. **No structured JSON logging.** Contract §13 wants JSON to stdout with `traceId`, `spanId`,
   `merchantId`, `correlationId`. `logging.pattern.level` carries trace ids, but a JSON encoder is a
   platform-wide concern (all nine services need the identical one) and belongs in a shared
   `logback-spring.xml` that does not exist yet. `merchantId` is not yet put on the MDC per request.
3. **No `traceparent` generation.** The header is propagated when a caller supplies one, but nothing
   here starts a trace. Adding `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` to
   the POM would activate the `management.otlp.tracing.*` settings already in `application.yml`.
4. **No rate limiting.** `pdei:ratelimit:{merchantId}:{window}` (contract §12) is unused by this
   service; the intake is currently unthrottled per merchant.
5. **No request-size cap on JSON bodies.** The batch cap bounds the event *count*, not the byte
   size. A 1000-event batch of very large bodies is accepted; a servlet filter or
   `server.max-http-request-size` equivalent should bound it.
6. **Schema reload requires a restart.** `SchemaRegistry.reload()` is public and idempotent, but no
   endpoint or file watcher calls it. Deliberate for now: schemas are configuration, and an HTTP
   reload endpoint on an unauthenticated service is an attack surface.
7. **`/stats` counters are process-lifetime and in-memory.** They reset on restart (`since` reports
   when). Prometheus is the authoritative series.
8. **Webhook secrets come from configuration.** Fine for a local stack; a real deployment wants a
   secret manager and per-source key rotation with an overlap window, which the verifier does not
   support (one secret per source, no `previousSecret`).
9. **No integration test against real Kafka/Redis/Postgres.** Testcontainers is already managed in
   the reactor parent; a `@SpringBootTest` + Testcontainers suite asserting a real record on
   `pdei.raw.events.v1` and a real `pdei:idem:` key would close the loop the MockMvc tests leave
   open.
10. **`AggregateIdResolver` is heuristic.** Getting it wrong costs partition locality, not
    correctness (every consumer tolerates out-of-order events), but an adapter that knows its
    aggregate should send `aggregateId` explicitly rather than rely on the field-name search.
11. **`canonical-event.schema.json` is registered but unused here.** Ingestion never produces
    canonical events; the file exists because `/schemas/events` is the one place the whole platform
    looks, and `normalization-worker` / the Python service / the frontend are its real consumers.
