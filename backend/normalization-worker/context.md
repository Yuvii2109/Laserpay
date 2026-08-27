# normalization-worker — module context

> Reload file. Everything a future session needs to work on this module without re-deriving it.
> Normative references: `docs/PLATFORM-CONTRACT.md`, `docs/SHARED-LIBRARY-API.md`,
> `docs/event-catalog.md`. If this file and those disagree, those win.

---

## 1. Purpose

Turn source-shaped events into canonical events.

```
pdei.raw.events.v1  ──►  normalization-worker  ──►  pdei.canonical.events.v1
      (RawEventEnvelope)          │                       (CanonicalEvent)
                                  └────────────────►  pdei.dlq.v1  (DeadLetterEnvelope)
```

Every external system speaks its own dialect. This worker is the only place in PDEI that knows
those dialects. Everything downstream operates on `CanonicalEvent` and nothing else, which is what
makes adding a fifth PSP a single new class instead of a change rippling through seven services.

Java package root: `com.laserpay.pdei.normalization`. Port **8082**. Consumer group
**`pdei-normalization-worker`**.

### What this module deliberately does NOT do

- It does not depend on `evidence-core`. Translation must not be able to reach financial state.
- It creates no evidence, maintains no projections, computes no readiness.
- It writes to exactly one table: `pdei.processed_events`, for idempotency.
- It never publishes to the evidence / dispute / readiness / case topics. Everything it produces
  goes to `pdei.canonical.events.v1`; state-builder-worker does the fan-out from there.

---

## 2. Responsibilities

1. **Consume** `pdei.raw.events.v1` with manual acknowledgement and at-least-once semantics.
2. **Deduplicate** on `rawEventId` through `ProcessedEventRepository.markProcessed` (Postgres,
   authoritative) fronted by a Redis completion cache.
3. **Upcast** legacy payload shapes forward through an `EventUpcaster` chain before any adapter
   sees them.
4. **Resolve** the owning `SourceAdapter` from `RawEventEnvelope.sourceSystem`.
5. **Translate** the source body into a `CanonicalEvent`, preserving `occurredAt` from the source
   and stamping `observedAt` at normalization time.
6. **Publish** to `pdei.canonical.events.v1`, keyed `merchantId + ":" + aggregateId`, with the
   contract headers and the W3C `traceparent`.
7. **Dead-letter** anything unmappable to `pdei.dlq.v1` with the original payload intact.

---

## 3. File-by-file map

```
backend/normalization-worker/
├── pom.xml                     module build; platform-common + platform-persistence + spring-kafka
├── Dockerfile                  multi-stage; build context is backend/, not this directory
├── context.md                  this file
└── src/
    ├── main/java/com/laserpay/pdei/normalization/
    │   ├── NormalizationWorkerApplication.java   Spring Boot entry point
    │   ├── NormalizationListener.java            @KafkaListener on pdei.raw.events.v1
    │   ├── NormalizationService.java             the pipeline: claim → upcast → adapt → publish
    │   ├── adapter/
    │   │   ├── SourceAdapter.java                the interface every source system implements
    │   │   ├── AbstractSourceAdapter.java        alias matching, id derivation, envelope assembly
    │   │   ├── SourceAdapterRegistry.java        resolves by sourceSystem; ambiguity is fatal
    │   │   ├── PspAdapter.java                   payments, refunds, disputes
    │   │   ├── OrderSystemAdapter.java           orders and line items
    │   │   ├── LogisticsAdapter.java             shipments, dispatch, delivery, geo
    │   │   ├── CrmAdapter.java                   customer communications
    │   │   ├── SimulatorAdapter.java             already-canonical vocabulary from the simulator
    │   │   ├── MerchantPortalAdapter.java        human-entered facts
    │   │   ├── CanonicalIds.java                 aggregateId/occurredAt from a canonical payload
    │   │   ├── DisputeReasonCodes.java           network/PSP reason code → DisputeReasonCode
    │   │   └── UnmappableEventException.java     non-retryable: routes to the DLQ
    │   ├── upcast/
    │   │   ├── EventUpcaster.java                one schema migration step
    │   │   ├── UpcasterChain.java                applies steps until stable, bounded at 10 passes
    │   │   ├── SchemaVersions.java               reads/writes the pdei-schema-version header
    │   │   ├── LegacyMinorUnitsUpcaster.java     v0 *_cents/*_paise → {amountMinor, currency}
    │   │   └── RetiredSourceEventTypeUpcaster.java  renames retired vendor event names
    │   ├── config/
    │   │   ├── NormalizationConfig.java          adapters, upcasters, registry, service beans
    │   │   ├── KafkaConsumerConfig.java          consumer/producer factories, error handler, DLQ
    │   │   └── NormalizationProperties.java      pdei.normalization.* binding
    │   ├── dlq/DeadLetterPublisher.java          writes DeadLetterEnvelope to pdei.dlq.v1
    │   ├── observability/KafkaTracing.java       W3C traceparent extract/inject + MDC
    │   └── support/
    │       ├── Payloads.java                     path-tolerant JSON reading; integer-only money
    │       ├── IdempotencyGuard.java             Redis cache + Postgres claim
    │       └── MonetaryPrecisionException.java   non-retryable money-parse failure
    ├── main/resources/application.yml
    └── test/java/com/laserpay/pdei/normalization/
        ├── RawEvents.java                        fixture builder
        ├── NormalizationServiceTest.java         duplicate delivery, replay determinism, lateness
        ├── adapter/*Test.java                    one per adapter + the registry
        ├── upcast/UpcasterChainTest.java
        └── support/PayloadsTest.java
```

---

## 4. Inbound contracts

| What | Detail |
|---|---|
| Topic | `pdei.raw.events.v1` (12 partitions), key `merchantId + ":" + idempotencyKey` |
| Payload | `com.laserpay.pdei.common.event.RawEventEnvelope`, consumed as `String` and parsed here |
| Consumer group | `pdei-normalization-worker` (`ConsumerGroups.PDEI_NORMALIZATION_WORKER`) |
| Headers read | `pdei-correlation-id`, `pdei-merchant-id`, `pdei-schema-version`, `pdei-attempt`, `traceparent` |
| Table written | `pdei.processed_events` — `(event_id, consumer_group)`, insert-only |
| Redis key | `pdei:idem:{eventId}` — completion cache, TTL 7d, never authoritative |

Producers of the raw topic: `ingestion-service`, `simulator-service`.

### Source systems recognised

| `sourceSystem` value (case/separator insensitive) | Adapter | `EventSource` stamped |
|---|---|---|
| `PSP`, `PSP_ADAPTER`, `PAYMENTS`, `stripe`, `razorpay`, `adyen`, `payu`, `cashfree` | `PspAdapter` | `PSP_ADAPTER` |
| `ORDER_SYSTEM`, `ORDERS`, `OMS`, `ERP`, `shopify`, `woocommerce`, `magento`, `unicommerce` | `OrderSystemAdapter` | `ORDER_SYSTEM` |
| `LOGISTICS`, `CARRIER`, `3PL`, `shiprocket`, `delhivery`, `bluedart`, `fedex`, `dhl` | `LogisticsAdapter` | `LOGISTICS` |
| `CRM`, `HELPDESK`, `SUPPORT`, `zendesk`, `freshdesk`, `intercom`, `gorgias` | `CrmAdapter` | `CRM` |
| `SIMULATOR`, `SIM`, `simulator-service` | `SimulatorAdapter` | `SIMULATOR` |
| `MERCHANT_PORTAL`, `PORTAL`, `MERCHANT`, `pdei-web` | `MerchantPortalAdapter` | `MERCHANT_PORTAL` |

An alias claimed by two adapters is a startup failure, not a warning: silent misrouting would send
a source system's events through the wrong vocabulary.

---

## 5. Outbound contracts

| Topic | Payload | When |
|---|---|---|
| `pdei.canonical.events.v1` | `CanonicalEvent` | every successfully normalized event |
| `pdei.dlq.v1` | `DeadLetterEnvelope` | unmappable event, malformed body, exhausted retries |

Partition key on both: `merchantId + ":" + aggregateId` (the DLQ reuses the inbound record key).
Headers written: `pdei-event-id`, `pdei-event-type`, `pdei-merchant-id`, `pdei-correlation-id`,
`pdei-schema-version`, `traceparent`; the DLQ adds `pdei-attempt`.

Canonical event types produced, and the source vocabulary that maps to each, are in
`docs/event-catalog.md` §1–§7. This worker produces no `READINESS`, `CASE` or `AUDIT` events, and
`SimulatorAdapter` explicitly refuses to accept them from outside.

### Metrics (PLATFORM-CONTRACT §13)

- `pdei_events_processed_total{service="normalization-worker",type,outcome}` — outcome is
  `success` | `duplicate` | `dead_lettered`
- `pdei_events_duplicate_total{service="normalization-worker"}`
- `pdei_event_processing_latency_seconds{service,type}`

---

## 6. Design decisions worth not re-litigating

### 6.1 The canonical `eventId` is deterministic

`AbstractSourceAdapter.canonicalEventId(rawEventId, eventType)` is a name-based UUID over
`pdei.normalization.v1|{rawEventId}|{eventType}`. Normalization is therefore a **pure function** of
the raw record, and replaying `pdei.raw.events.v1` re-emits byte-identical ids. Every downstream
consumer's `processed_events` claim collapses the repeat instead of double-applying it.

This is what makes "fix the adapter and replay history" a routine operation rather than a data
migration. Do not replace it with `Ids.eventId()`.

### 6.2 `occurredAt` and `observedAt` are different facts

`occurredAt` is read from the source payload (per-event-type field first, then a generic timestamp,
and only then the ingestion receipt time). `observedAt` is stamped once per pass from the injected
`Clocks`. The difference is *lateness*, and it is preserved deliberately: it is what makes the
out-of-order handling in state-builder-worker and the `DELAYED_EVENT` chaos type observable.

### 6.3 Money is parsed with integer arithmetic only

`Payloads.money` accepts minor-unit integers, snake/camel variants, and decimal **strings**
(converted by digit shifting in `minorFromDecimalText`). A JSON floating-point literal for a
monetary amount raises `MonetaryPrecisionException` and is dead-lettered — it is a producer bug, and
rounding it here would launder the bug into the ledger. Excess fraction digits are likewise
rejected, never truncated.

The currency is resolved once per event from the source object and threaded through every parse, so
a scalar amount is never paired with the configured fallback currency when the source stated one.

### 6.4 Publication is synchronous and inside the transaction

`normalizeAndPublish` is `@Transactional`. The idempotency claim and the Kafka send commit together:
a broker failure rolls the claim back and redelivery re-normalizes. Fire-and-forget publication
would let a claim commit for an event that never reached the canonical topic — an event silently
lost, which is the one failure mode this platform will not accept.

### 6.5 Values are consumed as `String`, not as a typed record

A deserialization failure inside the container is awkward to route: the record reaches the error
handler without a usable body. Parsing in the listener means an unparseable payload — a common real
failure — still reaches the DLQ with its original text preserved as a JSON string node.

### 6.6 Unmapped dispute reason codes are dead-lettered, not guessed

The reason code selects the evidence requirement profile, which drives readiness, which drives the
case decision. `DisputeReasonCodes.canonical` returns `null` for an unknown code and the adapter
raises `UnmappableEventException`. Extending the table plus a replay is the fix. By contrast, an
unrecognised *communication channel* degrades to `PORTAL`, because it is descriptive metadata that
no decision depends on. The distinction is deliberate: degrade what is cosmetic, refuse what is
load-bearing.

### 6.7 Redis is a completion cache, never a claim

`IdempotencyGuard` writes `pdei:idem:{eventId}` only **after** processing succeeds. A hit therefore
proves the event was fully handled. Claiming in Redis with SETNX *before* processing would be faster
and wrong: a crash between claim and work would leave an event that redelivery can never rescue.
Redis is optional throughout; its absence costs latency, not correctness.

---

## 7. Configuration

Bound from `pdei.normalization.*` into `NormalizationProperties`.

| Property | Default | Meaning |
|---|---|---|
| `default-currency` | `INR` | fallback when a source omits the currency on a monetary field |
| `publish-timeout` | `10s` | broker acknowledgement wait before failing the batch |
| `lateness-warn-threshold` | `30m` | lag beyond which an event is logged as unusually late |
| `concurrency` | `3` | consumer threads (12 partitions divide evenly) |
| `max-poll-records` | `100` | records per poll |
| `retry.max-attempts` | `4` | total delivery attempts before dead-lettering |
| `retry.initial-interval` | `1s` | exponential backoff start |
| `retry.multiplier` | `2.0` | backoff multiplier |
| `retry.max-interval` | `30s` | backoff ceiling |
| `idempotency.redis-enabled` | `true` | set false to rely on Postgres alone |
| `idempotency.ttl` | `7d` | matches `pdei:idem:{eventId}` in contract §12 |

### Environment variables (contract §15)

`PDEI_POSTGRES_URL`, `PDEI_POSTGRES_USER`, `PDEI_POSTGRES_PASSWORD`, `PDEI_KAFKA_BOOTSTRAP`,
`PDEI_REDIS_URL`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, plus the module-local
`PDEI_DEFAULT_CURRENCY`.

---

## 8. Dependencies on other modules

| Module | Why |
|---|---|
| `platform-common` | `CanonicalEvent`, `RawEventEnvelope`, `DeadLetterEnvelope`, `EventType`, `EventSource`, `Topics`, `ConsumerGroups`, `EventHeaders`, `Json`, `Money`, `IdPrefix`, `Clocks`, `MetricNames` |
| `platform-persistence` | `ProcessedEventRepository` and the `pdei` schema (Flyway) |
| `evidence-core` | **not a dependency, on purpose** |

Runtime infrastructure: Kafka (required), PostgreSQL (required), Redis (optional).

---

## 9. Build and run

```bash
# unit tests
cd backend && mvn -pl normalization-worker -am test

# package
cd backend && mvn -pl normalization-worker -am package -DskipTests

# run against the local stack
java -jar normalization-worker/target/normalization-worker.jar

# container (build context is backend/, not this directory)
cd backend && docker build -f normalization-worker/Dockerfile -t pdei/normalization-worker:dev .
docker run --rm --network pdei-net -p 8082:8082 \
  -e PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
  -e PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
  pdei/normalization-worker:dev
```

Health: `http://localhost:8082/actuator/health` · Metrics: `/actuator/prometheus`.

---

## 10. Extension points

| To do this | Do this |
|---|---|
| Add a source system | Implement `SourceAdapter` (extend `AbstractSourceAdapter`), declare a `@Bean` in `NormalizationConfig`. The registry picks it up; no other file changes. |
| Add a vendor alias for an existing source | Add it to that adapter's `ALIASES`. A collision fails startup. |
| Support a new source event name | Add an entry to that adapter's `MAPPINGS`. |
| Handle a retired vendor event name | Add it to `RetiredSourceEventTypeUpcaster.RENAMES`. |
| Migrate an old payload shape | Implement `EventUpcaster`, declare it as a `@Bean`. `UpcasterChain` orders by `fromVersion()`. Keep `upcast()` idempotent — `supports()` must become false afterwards. |
| Add a network reason code | Add it to `DisputeReasonCodes.TABLE`, then replay the dead letters. |
| Change money-shape tolerance | `Payloads.money` / `Payloads.minorFromDecimalText`. Do not introduce floating point. |
| Replay after a fix | Reset the `pdei-normalization-worker` group offsets (or delete its `processed_events` rows) and let the topic replay. Deterministic ids make this safe. |

---

## 11. Known gaps and TODOs

1. **No JSON Schema validation of raw bodies.** `schemas/events/` is empty; when source schemas
   land there, validate in the listener before the upcaster chain and dead-letter on violation.
   Today an adapter's missing-field failure is the only schema check.
2. **`pdei_kafka_consumer_lag{group,topic}` is not published by this module.** It is currently
   expected from the Kafka exporter / Micrometer Kafka metrics; if the contract wants it emitted
   per service, register a gauge over `KafkaListenerEndpointRegistry` container metrics.
3. **`DeadLetterPublisher` has no replay tool.** Records land in `pdei.dlq.v1` with everything
   needed to replay, but the replay endpoint lives in `simulator-service`
   (`POST /sim/v1/replay`) and is not yet wired to this topic.
4. **Upcaster versioning is header-based.** `RawEventEnvelope` has no `schemaVersion` field, so the
   version lives in `pdei-schema-version` with the body as a fallback. If the shared record ever
   gains the field, `SchemaVersions` is the single place to change.
5. **`MerchantPortalAdapter` covers business facts only.** Portal *file* uploads go directly to
   `POST /api/v1/evidence` in api-gateway-service; there is no raw-topic path for bytes, by design.
6. **Adapter coverage is representative, not exhaustive.** Each adapter models the shapes named in
   `docs/event-catalog.md` plus common vendor variants. Real integrations will need their exact
   payloads added to the `MAPPINGS` tables and the candidate-path lists in the mappers.
7. **No integration test against a real broker.** Adapter and pipeline behaviour is unit-tested;
   an end-to-end Testcontainers test (raw topic in, canonical topic out, DLQ on failure) is the
   obvious next addition and belongs beside `platform-persistence`'s Testcontainers tests.
8. **Geo conversion truncates beyond six decimal places** (`LogisticsAdapter.shiftDecimal`).
   Micro-degrees are roughly 11 cm at the equator, which is far finer than any delivery proof
   needs, but it is a truncation and is recorded here as one.
