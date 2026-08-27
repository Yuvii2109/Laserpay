# state-builder-worker — module context

> Reload file. Everything a future session needs to work on this module without re-deriving it.
> Normative references: `docs/PLATFORM-CONTRACT.md`, `docs/SHARED-LIBRARY-API.md`,
> `docs/event-catalog.md`. If this file and those disagree, those win.

---

## 1. Purpose

Turn the canonical event stream into queryable financial state, and capture evidence at the moment
the fact happens.

```
                      ┌──────────────────────────────────────────┐
pdei.canonical.       │            state-builder-worker          │
  events.v1  ────────►│  per-aggregate handlers + out-of-order   │
                      │  guard + derived evidence                │
                      └───┬───────────┬──────────────┬───────────┘
                          │           │              │
              PostgreSQL  │           │  pdei.evidence.events.v1
              projections ▼           │              │  pdei.dispute.events.v1
       payments, orders, order_lines, │              ▼
       shipments, deliveries, refunds,│      readiness-worker, case-orchestrator-service
       communications, disputes,      │
       transactions                   └──► pdei.dlq.v1 on failure
```

The product thesis lives here. A dispute arrives 45 days after the purchase; hunting for evidence
then is archaeology. This worker records the payment proof, the order record, the shipping record,
the delivery proof, the refund receipt and the customer communication **as each fact happens**, so
assembling a case later is a database read.

Java package root: `com.laserpay.pdei.statebuilder`. Port **8083**. Consumer group
**`pdei-state-builder-worker`**.

---

## 2. Responsibilities

1. **Consume** `pdei.canonical.events.v1`, manual ack, at-least-once.
2. **Deduplicate** on `eventId` (`processed_events` + a Redis completion cache).
3. **Project** each aggregate into its table, guarded by a per-row watermark so late and duplicate
   events cannot corrupt newer state.
4. **Roll up** the transaction: captured and refunded totals recomputed from child rows, status
   promoted monotonically.
5. **Derive evidence** from lifecycle facts through `evidence-core`'s `EvidenceService`.
6. **Forward** EVIDENCE events to `pdei.evidence.events.v1` and DISPUTE events to
   `pdei.dispute.events.v1`.
7. **Dead-letter** anything unprocessable to `pdei.dlq.v1`.

### What this module deliberately does NOT do

- It never writes the `evidence`, `evidence_versions` or `evidence_relationships` tables directly.
  All evidence goes through `EvidenceService`, which hashes content, versions it and audits it.
- It never computes readiness (readiness-worker) or opens cases (case-orchestrator-service).
- It never calls the AI service. AI cannot reach financial state, and this is the component that
  owns financial state.

---

## 3. File-by-file map

```
backend/state-builder-worker/
├── pom.xml                      platform-common + platform-persistence + evidence-core + spring-kafka
├── Dockerfile                   multi-stage; build context is backend/, not this directory
├── context.md                   this file
└── src/
    ├── main/java/com/laserpay/pdei/statebuilder/
    │   ├── StateBuilderWorkerApplication.java  Spring Boot entry point
    │   ├── StateBuilderListener.java           @KafkaListener on pdei.canonical.events.v1
    │   ├── StateBuilderService.java            the transaction: claim → dispatch → confirm
    │   ├── StateBuilderDispatcher.java         EnumMap<EventType, AggregateEventHandler>
    │   ├── handler/
    │   │   ├── AggregateEventHandler.java      the contract every handler satisfies
    │   │   ├── PaymentEventHandler.java        PAYMENT + PAYMENT_PROOF, AVS_CVV_RESULT, DEVICE_FINGERPRINT
    │   │   ├── OrderEventHandler.java          ORDER + order_lines + ORDER_RECORD
    │   │   ├── ShipmentEventHandler.java       SHIPMENT + deliveries + SHIPPING_RECORD, DELIVERY_PROOF
    │   │   ├── RefundEventHandler.java         REFUND + REFUND_RECEIPT
    │   │   ├── CommunicationEventHandler.java  COMMUNICATION + CUSTOMER_COMMUNICATION
    │   │   ├── DisputeEventHandler.java        DISPUTE + forward to pdei.dispute.events.v1
    │   │   └── EvidenceEventHandler.java       forward to pdei.evidence.events.v1
    │   ├── projection/
    │   │   ├── ProjectionWatermark.java        THE out-of-order rule (see §5)
    │   │   ├── TransactionProjection.java      transaction row + recomputed money rollups
    │   │   ├── TransactionStatus.java          the monotonic status ladder
    │   │   └── ReferenceData.java              on-demand parent rows for foreign keys
    │   ├── evidence/DerivedEvidenceService.java  lifecycle fact → deterministic evidence document
    │   ├── forward/EventForwarder.java           identical re-publication to a downstream topic
    │   ├── config/
    │   │   ├── StateBuilderConfig.java         handlers, projections, dispatcher, service
    │   │   ├── KafkaConsumerConfig.java        consumer/producer, error handler, DLQ recoverer
    │   │   └── StateBuilderProperties.java     pdei.state-builder.* binding
    │   ├── dlq/DeadLetterPublisher.java        writes DeadLetterEnvelope to pdei.dlq.v1
    │   ├── observability/KafkaTracing.java     W3C traceparent extract/inject + MDC
    │   └── support/
    │       ├── CanonicalPayloads.java          strict readers for canonical payloads
    │       └── IdempotencyGuard.java           Redis cache + Postgres claim
    ├── main/resources/application.yml
    └── test/java/com/laserpay/pdei/statebuilder/
        ├── Events.java                         canonical event fixtures
        ├── Repositories.java                   in-memory Spring Data stubs
        ├── EvidenceStubs.java                  recording EvidenceService stand-in
        ├── StateBuilderServiceTest.java        duplicate delivery, skipped types, rollback
        ├── projection/ProjectionWatermarkTest.java  the four rules, stated as tests
        ├── evidence/DerivedEvidenceServiceTest.java determinism, provenance, degradation
        └── handler/{Payment,Shipment,Refund,Dispute}EventHandlerTest.java
```

---

## 4. Inbound and outbound contracts

### Inbound

| What | Detail |
|---|---|
| Topic | `pdei.canonical.events.v1` (12 partitions), key `merchantId + ":" + aggregateId` |
| Payload | `CanonicalEvent`, consumed as `String` and parsed here |
| Consumer group | `pdei-state-builder-worker` |
| Headers read | `pdei-merchant-id`, `pdei-correlation-id`, `pdei-attempt`, `traceparent` |

Event types handled: all PAYMENT, ORDER, SHIPMENT, REFUND, COMMUNICATION, EVIDENCE and DISPUTE
types (contract §3.1). READINESS, CASE and AUDIT types are skipped — they are other services'
output, not this worker's input.

### Tables written

| Table | Written by |
|---|---|
| `transactions` | `TransactionProjection` (all handlers) |
| `payments` | `PaymentEventHandler`, and as stubs by `ReferenceData` |
| `orders`, `order_lines` | `OrderEventHandler`, and as stubs by `ReferenceData` |
| `shipments`, `deliveries` | `ShipmentEventHandler`, and as stubs by `ReferenceData` |
| `refunds` | `RefundEventHandler` |
| `communications` | `CommunicationEventHandler` |
| `disputes` | `DisputeEventHandler` |
| `merchants`, `customers` | `ReferenceData` (stubs only) |
| `processed_events` | `IdempotencyGuard` |
| `evidence`, `evidence_versions`, `audit_events` | **indirectly**, via `evidence-core` |

### Outbound topics

| Topic | Payload | When |
|---|---|---|
| `pdei.evidence.events.v1` | `CanonicalEvent` (`EvidenceAdded`) | published by `EvidenceService` on every derived artifact; `EvidenceEventHandler` also forwards externally-sourced EVIDENCE events here |
| `pdei.dispute.events.v1` | `CanonicalEvent` (DISPUTE types) | every DISPUTE event, forwarded unchanged |
| `pdei.audit.events.v1` | `AuditEvent` | published by `evidence-core`'s `AuditRecorder` |
| `pdei.dlq.v1` | `DeadLetterEnvelope` | unprocessable record or exhausted retries |

### Derived evidence table

| Lifecycle fact | Evidence derived | Condition |
|---|---|---|
| `PaymentCaptured` | `PAYMENT_PROOF` | always |
| `PaymentAuthorized` | `AVS_CVV_RESULT` | payload carries `avsResult` or `cvvResult` |
| `PaymentAuthorized` | `DEVICE_FINGERPRINT` | payload carries `deviceFingerprint` |
| `OrderCreated` | `ORDER_RECORD` | always |
| `ShipmentDispatched` | `SHIPPING_RECORD` | always |
| `ShipmentDelivered` | `DELIVERY_PROOF` | always |
| `RefundProcessed` | `REFUND_RECEIPT` | always |
| `CommunicationCreated` / `CommunicationReceived` | `CUSTOMER_COMMUNICATION` | transaction is known |

Nothing is derived without a transaction: evidence in this platform always belongs to one.

### Metrics (PLATFORM-CONTRACT §13)

- `pdei_events_processed_total{service="state-builder-worker",type,outcome}` — outcome is
  `success` | `duplicate` | `skipped` | `dead_lettered`
- `pdei_events_duplicate_total{service="state-builder-worker"}`
- `pdei_event_processing_latency_seconds{service,type}`
- `pdei_evidence_total{type,status}` — emitted by `evidence-core` on every derived artifact

---

## 5. THE out-of-order rule

**Every projection row carries the id and `occurredAt` of the last event applied to it.** Given that
watermark and an arriving event, `ProjectionWatermark.shouldApply` decides:

1. **No watermark** (new row, or a stub created for a foreign key) → **apply**.
2. **`event.eventId == lastEventId`** → the same event again (redelivery, replay) → **ignore**.
3. **`event.occurredAt < lastEventOccurredAt`** → the event describes an older fact → **ignore**.
   Newer state is never overwritten by an older fact.
4. **Otherwise** → **apply**. Equal `occurredAt` with a different `eventId` counts as applicable:
   two distinct facts can share an instant and refusing both would lose one.

**Why `occurredAt` and not arrival order.** The partition key is `merchantId + ":" + aggregateId`,
so events about *one* aggregate arrive in order. Events about *different* aggregates of the same
transaction do not — `docs/event-catalog.md` §12 says so explicitly — and a source system that was
offline for six hours replays its backlog in bulk. The source's own `occurredAt` is the only
ordering that survives both.

**Where the watermark lives.** In the row's `metadata` JSONB column, under `lastEventId` and
`lastEventOccurredAt`. `transactions` and `disputes` additionally have dedicated `last_event_id`
columns, which the handlers keep in sync. The other tables carry it in `metadata` only, because
`platform-persistence` owns the schema and this module does not add migrations to it — see
"Known gaps" #1.

**The consequence, stated plainly.** A stale event is dropped in full, not merged field-by-field.
If `PaymentCaptured` is processed before the `PaymentCreated` that carried the card metadata, that
metadata is not back-filled. This is the deliberate trade-off: the newest state is preserved
absolutely, at the cost of some enrichment from late-arriving older events. Readiness recomputes
from whatever the row holds, so the system converges rather than corrupting. Field-level backfill
is "Known gaps" #2.

**Replay safety.** Every handler is safe to re-run over history: rows are upserted by deterministic
id, money rollups are recomputed from child rows rather than incremented, and derived evidence
deduplicates on content hash. Resetting the consumer group and replaying the canonical topic
converges on the same database.

---

## 6. Other design decisions worth not re-litigating

### 6.1 Rollups are recomputed, never incremented

`transactions.captured_amount_minor` is summed from `payments` rows in state `CAPTURED`;
`refunded_amount_minor` comes from `RefundRepository.sumProcessedAmountMinor`. An accumulator
(`captured += amount`) would be shorter and wrong — a redelivered `PaymentCaptured` would double the
total with no way to notice afterwards. All arithmetic is exact integer arithmetic on `long` minor
units.

### 6.2 Stub rows instead of dropped foreign keys or buffered events

`payments.transaction_id`, `shipments.order_id`, `deliveries.shipment_id` and
`customers.merchant_id` are real foreign keys, and events arrive out of order across aggregates.
`ReferenceData` creates a minimal parent row on demand: zero money, earliest status, timestamps from
the event that forced it, `metadata.pdeiStub = true`, and **no watermark** — so the real event fills
it in properly whenever it lands. Stubs are visible rather than hidden, which makes "how much of
this projection is inferred?" an SQL query.

### 6.3 Derived evidence is content-addressed and therefore idempotent

`DerivedEvidenceService` builds the artifact's bytes as canonical JSON (sorted keys) over
**event-derived fields only** — no clock reading, no random id, and deliberately not `observedAt`.
`EvidenceService.createEvidence` deduplicates on `(sha256, transactionId)`, so a replayed event
returns the existing artifact rather than creating a second one. Idempotency is a property of the
content, not of a lock.

### 6.4 Only the transaction row needs a status ladder

Per-aggregate rows are written only by events about that aggregate, which share a partition key and
therefore arrive in order — the watermark suffices and status is set directly. The transaction row
is written by payments, refunds and disputes, which are different aggregates on different
partitions with nothing ordering them, so its status moves through `TransactionStatus.promote` and
never regresses.

### 6.5 One transaction spans the claim, the projections and the forwards

A crash before commit leaves no claim (redelivery re-processes); after commit, the claim suppresses
the repeat; a broker failure while forwarding throws and rolls the projection back. The MinIO write
inside evidence derivation is the one effect outside the transaction, and it is safe in that
direction: the object is written before the row, so a rollback leaves an orphaned object (harmless,
reclaimable, content-addressed) rather than a row pointing at nothing.

### 6.6 A dispute event is forwarded even when the projection is suppressed

If a DISPUTE event is stale or arrives before its `DisputeCreated`, the projection is skipped but the
event is still forwarded. The orchestrator dedupes on `eventId`, and dropping a forward could strand
a workflow waiting on exactly that signal. Losing a signal is worse than a redundant one.

### 6.7 Unhandled event types are skipped, not dead-lettered

READINESS, CASE and AUDIT events on the canonical topic are other services working correctly.
Dead-lettering them would fill the DLQ with healthy traffic. They are counted `outcome="skipped"`.

---

## 7. Configuration

Bound from `pdei.state-builder.*` into `StateBuilderProperties`.

| Property | Default | Meaning |
|---|---|---|
| `default-currency` | `INR` | currency for a stub row created before any event stated one; only ever paired with a zero amount |
| `publish-timeout` | `10s` | broker acknowledgement wait when forwarding |
| `concurrency` | `3` | consumer threads; parallelises across aggregates, never reorders one |
| `max-poll-records` | `50` | modest, because every record does database work |
| `derive-evidence` | `true` | set false to project state without deriving evidence |
| `retry.max-attempts` | `5` | optimistic-locking clashes are the common retry case |
| `retry.initial-interval` | `1s` | |
| `retry.multiplier` | `2.0` | |
| `retry.max-interval` | `30s` | |
| `idempotency.redis-enabled` | `true` | false relies on Postgres alone |
| `idempotency.ttl` | `7d` | matches `pdei:idem:{eventId}` in contract §12 |

`pdei.core.*` (from `evidence-core`) is also set in `application.yml`: MinIO endpoint and
credentials for evidence storage, and `audit.publish-to-kafka: true`.

### Environment variables (contract §15)

`PDEI_POSTGRES_URL`, `PDEI_POSTGRES_USER`, `PDEI_POSTGRES_PASSWORD`, `PDEI_KAFKA_BOOTSTRAP`,
`PDEI_REDIS_URL`, `PDEI_MINIO_ENDPOINT`, `PDEI_MINIO_ACCESS_KEY`, `PDEI_MINIO_SECRET_KEY`,
`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, plus the module-local `PDEI_DEFAULT_CURRENCY`.

---

## 8. Dependencies on other modules

| Module | Why |
|---|---|
| `platform-common` | `CanonicalEvent`, `EventType`, `Topics`, `ConsumerGroups`, `EventHeaders`, `Money`, `IdPrefix`, `Json`, `MetricNames` |
| `platform-persistence` | every projection entity and repository, plus `ProcessedEventRepository` and the Flyway-owned schema |
| `evidence-core` | `EvidenceService` (evidence creation, hashing, versioning, audit, `EvidenceAdded` publication); its `KafkaEventPublisher` uses the `KafkaTemplate` declared in `KafkaConsumerConfig` |

Runtime infrastructure: Kafka, PostgreSQL, MinIO (evidence storage) — all required; Redis optional.

Downstream consumers of what this worker produces: `readiness-worker` (evidence topic),
`case-orchestrator-service` (dispute topic), `audit-service` (audit topic),
`api-gateway-service` (reads the projections).

---

## 9. Build and run

```bash
# unit tests
cd backend && mvn -pl state-builder-worker -am test

# package
cd backend && mvn -pl state-builder-worker -am package -DskipTests

# run against the local stack
java -jar state-builder-worker/target/state-builder-worker.jar

# container (build context is backend/, not this directory)
cd backend && docker build -f state-builder-worker/Dockerfile -t pdei/state-builder-worker:dev .
docker run --rm --network pdei-net -p 8083:8083 \
  -e PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
  -e PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
  -e PDEI_MINIO_ENDPOINT=http://minio:9000 \
  pdei/state-builder-worker:dev
```

Health: `http://localhost:8083/actuator/health` · Metrics: `/actuator/prometheus`.

---

## 10. Extension points

| To do this | Do this |
|---|---|
| Project a new aggregate | Implement `AggregateEventHandler`, declare a `@Bean` in `StateBuilderConfig`. The dispatcher indexes it; a claimed `EventType` collision fails startup. |
| Derive a new evidence type | Call `derivedEvidenceService.derive(event, EvidenceType.X, transactionId, relatedEntityId, summary)` from the handler that observes the fact. Keep the summary factual: it reaches the AI context. |
| Change the out-of-order rule | `ProjectionWatermark.shouldApply` — one method, and update §5 of this file with it. |
| Add a transaction status | `TransactionStatus.LADDER` plus the `ck_transactions_status` constraint in `V2__transactions.sql` (owned by platform-persistence). |
| Forward to another topic | `EventForwarder.forward(topic, event)` from the relevant handler. |
| Add a parent-row stub | `ReferenceData.ensureX` — zero money, earliest status, `stubMetadata(reason)`, no watermark. |
| Replay history | Reset the `pdei-state-builder-worker` group offsets (or delete its `processed_events` rows) and let the topic replay. Watermarks and content-addressed evidence make this converge. |

---

## 11. Known gaps and TODOs

1. **The watermark lives in `metadata` JSONB, not in dedicated columns.** The contract's intent is
   `last_event_occurred_at` / `last_event_id` per projection row; only `transactions` and `disputes`
   have a `last_event_id` column today, and no table has `last_event_occurred_at`. This module does
   not add Flyway migrations because `platform-persistence` owns the schema. When that module adds
   the columns, `ProjectionWatermark` is the single place to change, plus one setter call per
   handler. Querying "what did this row last see?" is a JSONB lookup until then.
2. **Stale events are dropped, not field-merged.** See §5. A late-arriving older event that carries
   fields the newer one lacks (card metadata on `PaymentCreated` after `PaymentCaptured`) does not
   back-fill them. A `backfillNullFieldsOnly` path on the stale branch is the obvious refinement.
3. **`order_lines` are never deleted.** A redelivered `OrderCreated` upserts by
   `{orderId}-L{n}`; lines the payload no longer contains are left in place, because inferring a
   deletion from an absent array would silently drop history. Order amendments need their own event.
4. **No cross-aggregate contradiction detection here.** "delivered before dispatched", "refunded more
   than captured" and address mismatches are flagged by `evidence-core`'s `ContradictionDetector`,
   which reads these projections. This worker's job is to make the numbers true, not to judge them.
5. **`pdei_kafka_consumer_lag{group,topic}` is not published by this module** — same gap as
   normalization-worker.
6. **No integration test against real infrastructure.** Handlers are unit-tested against in-memory
   repositories; an end-to-end Testcontainers test (canonical topic in → Postgres rows + evidence
   topic out) is the obvious next addition.
7. **`KafkaTracing`, `IdempotencyGuard` and `DeadLetterPublisher` are duplicated** between this
   module and normalization-worker. They are worker-infrastructure, not domain, and belong in a
   shared module; they were not put in `platform-common` because that module is deliberately free of
   Spring and Kafka dependencies. A `platform-worker` module is the natural home.
8. **Optimistic-locking retries are untested under real concurrency.** Two consumer threads applying
   events for different aggregates of the same transaction will contend on the `transactions` row.
   The retry policy handles it, but the behaviour under sustained contention has not been measured —
   and measuring rather than asserting is a house rule.
9. **A transaction with no stated amount stays at zero** until a payment event supplies one. Dispute
   and communication events deliberately do not set it: a disputed amount is not necessarily the
   transaction amount.
