# `backend/readiness-worker` — Evidence Readiness Worker

> Module context. Normative sources, in precedence order:
> `docs/PLATFORM-CONTRACT.md` → `docs/SHARED-LIBRARY-API.md` → `planner/pre-dispute-evidence-intelligence-reference.md`.
> Where this file and those disagree, those win and this file is stale.

| Item | Value |
|---|---|
| Maven artifactId | `readiness-worker` |
| Java package root | `com.laserpay.pdei.readiness` |
| Port | **8084** (host and container) |
| Kind | Spring Boot worker (web starter present only for actuator) |
| Consumer group | `pdei-readiness-worker` |
| Health | `GET /actuator/health` |
| Metrics | `GET /actuator/prometheus` |

---

## 1. Purpose

Readiness is a **continuously maintained property of a transaction**, not a report someone runs.
This service is the thing that keeps it current.

It answers, at any instant, for any transaction: what evidence is present, what is missing, what has
expired, what is contradictory, and what would prevent an automated representment (reference §13).
It does so **deterministically** — the same inputs always produce the same score — by delegating the
arithmetic to `evidence-core`'s `ReadinessEngine` (PLATFORM-CONTRACT §7) and owning only the
*process* around it: when to recompute, how to avoid recomputing twenty times for one burst, where
to persist, what to publish.

Two rules shape everything here:

- **No AI.** This worker contains no model call and no probabilistic anything. It is the
  deterministic half of the platform (non-negotiable rules 1, 2, 6).
- **Readiness never writes financial state.** It reads transactions, evidence and policy; it writes
  snapshots, gaps and evidence *lifecycle* transitions. It never creates or amends a payment,
  order, shipment or refund.

## 2. Responsibilities

1. **Consume** `pdei.evidence.events.v1` and `pdei.canonical.events.v1`, idempotently.
2. **Debounce** recomputation per transaction — in-process (`RecomputeDebouncer`) and across
   replicas (`pdei:lock:readiness:{transactionId}`), so an event burst causes one computation.
3. **Compute** through `ReadinessEngine.compute(transactionId, reasonCode?)`.
4. **Persist** a `readiness_snapshots` row plus its `readiness_gaps` rows, in one transaction,
   append-only, with the previous snapshot's `is_current` cleared.
5. **Project** the score onto `transactions.readiness_score / readiness_band / readiness_computed_at`
   for the transaction list and band filter.
6. **Cache** to `pdei:readiness:{transactionId}` with a 10-minute TTL.
7. **Publish** `ReadinessRecomputed` and `ReadinessGapDetected` to `pdei.readiness.events.v1`, plus
   an `AuditEvent` per recomputation to `pdei.audit.events.v1`.
8. **Sweep** evidence expiry on a schedule: ACTIVE → EXPIRING → EXPIRED, emitting `EvidenceExpired`.
9. **Scan** periodically to materialise the at-risk feed and to repair stale or never-computed
   snapshots.

## 3. File map

```
backend/readiness-worker/
├── pom.xml
├── Dockerfile                       build context is backend/, not this directory
├── context.md                       this file
└── src/
    ├── main/java/com/laserpay/pdei/readiness/
    │   ├── ReadinessWorkerApplication.java   @SpringBootApplication + @EnableScheduling
    │   ├── config/
    │   │   ├── ReadinessProperties.java      pdei.readiness.* (debounce, lock, sweep, at-risk)
    │   │   ├── ReadinessWorkerConfig.java     bean wiring; overrides two evidence-core ports
    │   │   └── KafkaConfig.java               producer, consumer factory, DLQ error handler
    │   ├── consume/
    │   │   ├── EvidenceEventConsumer.java     @KafkaListener pdei.evidence.events.v1
    │   │   ├── CanonicalEventConsumer.java    @KafkaListener pdei.canonical.events.v1
    │   │   ├── EventIntake.java               shared: dedupe → resolve → schedule
    │   │   ├── IdempotencyGuard.java          Redis SETNX + processed_events claim
    │   │   └── DeadLetterPublisher.java       pdei.dlq.v1
    │   ├── recompute/
    │   │   ├── RecomputeTrigger.java          enum; matches ck_readiness_snapshots_trigger
    │   │   ├── RecomputeRequest.java          immutable, mergeable request
    │   │   ├── RecomputeDebouncer.java        sliding window with a ceiling; owns its executors
    │   │   ├── RecomputeLock.java             pdei:lock:readiness:{txId}, tri-state acquisition
    │   │   ├── ReadinessCache.java            pdei:readiness:{txId}, 10 min
    │   │   └── ReadinessRecomputeService.java compute → persist → project → cache → publish
    │   ├── persistence/
    │   │   ├── Sql.java                       package-private JDBC conversions
    │   │   ├── ReadinessStore.java            V6-accurate snapshots + gaps + at-risk queries
    │   │   ├── JdbcReadinessDataProvider.java  feeds ReadinessEngine from V2/V3/V4
    │   │   ├── EvidenceExpiryStore.java       interface (lifecycle slice of pdei.evidence)
    │   │   ├── JdbcEvidenceExpiryStore.java   implementation
    │   │   └── TransactionResolver.java       aggregate id → transaction id
    │   ├── publish/
    │   │   └── ReadinessEventPublisher.java   every event this worker emits
    │   ├── sweep/
    │   │   ├── ExpirySweepJob.java            scheduled cron expiry sweep
    │   │   ├── AtRiskScanner.java             periodic feed materialisation + staleness repair
    │   │   └── AtRiskEntry.java               one row of the at-risk feed
    │   └── metrics/
    │       └── ReadinessWorkerMetrics.java    contract §13 names via MetricNames
    ├── main/resources/application.yml
    └── test/java/com/laserpay/pdei/readiness/
        ├── recompute/RecomputeDebouncerTest.java        burst → one recomputation
        ├── sweep/ExpirySweepJobTest.java                lifecycle transitions, idempotency
        ├── sweep/InMemoryEvidenceExpiryStore.java       compare-and-set store double
        ├── sweep/RecordingEventPublisher.java           EventPublisherPort double
        └── consume/EventRelevanceTest.java              no feedback loop, trigger classification
```

## 4. Inbound contracts

### 4.1 Kafka topics consumed

| Topic | Why | Event types acted on |
|---|---|---|
| `pdei.evidence.events.v1` | the evidence set changed | `EvidenceAdded`, `EvidenceExpired`, `EvidenceInvalidated` |
| `pdei.canonical.events.v1` | a linked entity changed | PAYMENT, ORDER, SHIPMENT, REFUND, COMMUNICATION, DISPUTE types |

Ignored by design: `READINESS`, `CASE` and `AUDIT` event types. Consuming our own output would be an
infinite recomputation loop (`EventIntake.isRelevant`, asserted exhaustively in `EventRelevanceTest`).

Idempotency: Redis `SETNX pdei:idem:{eventId}` (TTL 7d) in front of
`ProcessedEventRepository.markProcessed(eventId, "pdei-readiness-worker")`. Postgres is authoritative;
a Redis hit is re-checked against Postgres so a crash between the two cannot silently drop an event.

Acknowledgement is `MANUAL_IMMEDIATE`, after the claim. Failures are retried three times, then
dead-lettered to `pdei.dlq.v1` and acknowledged — a poison record must not stall a partition, because
partitions are keyed `merchantId + ":" + aggregateId` and would freeze one merchant entirely.

### 4.2 Tables read

`pdei.transactions`, `pdei.payments`, `pdei.orders`, `pdei.order_lines`, `pdei.shipments`,
`pdei.deliveries`, `pdei.refunds`, `pdei.communications`, `pdei.evidence`, `pdei.disputes`,
`pdei.policies` (via `PolicyEngine`), `pdei.processed_events`.

### 4.3 Redis keys read/written

| Key | Direction | TTL |
|---|---|---|
| `pdei:idem:{eventId}` | write (SETNX) | 7 days |
| `pdei:readiness:{transactionId}` | write / evict | 10 minutes |
| `pdei:lock:readiness:{transactionId}` | write (SET NX PX) | 30 seconds |

No key outside PLATFORM-CONTRACT §12 is invented. The at-risk feed is materialised **in memory per
replica** rather than into a new Redis key precisely for this reason; the authoritative rows are
already in `readiness_gaps`.

## 5. Outbound contracts

### 5.1 Kafka

| Topic | Event | When |
|---|---|---|
| `pdei.readiness.events.v1` | `ReadinessRecomputed` | every completed computation |
| `pdei.readiness.events.v1` | `ReadinessGapDetected` | when the snapshot carries gaps at or above `gap-event-min-severity` (default HIGH) |
| `pdei.evidence.events.v1` | `EvidenceExpired` | expiry sweep moved an artifact to EXPIRED |
| `pdei.audit.events.v1` | `AuditEvent` | `READINESS_RECOMPUTED`, `EVIDENCE_EXPIRED`, `EVIDENCE_EXPIRING` |
| `pdei.dlq.v1` | `DeadLetterEnvelope` | retries exhausted |

Partition key on every message: `merchantId + ":" + aggregateId`. Readiness events use the
**transaction** as the aggregate (`EventType.ReadinessRecomputed.aggregateType() == TRANSACTION`), so
one transaction's evidence changes, recomputations and expiries stay in one ordered stream.

The worker does **not** write `audit_events` itself. audit-service owns the per-merchant hash chain;
two writers appending to one chain would fork it.

### 5.2 Tables written

| Table | Operation |
|---|---|
| `pdei.readiness_snapshots` | INSERT (append-only) + clear `is_current` on the previous row |
| `pdei.readiness_gaps` | UPSERT by deterministic `gap_id`; absent gaps marked `resolved` |
| `pdei.transactions` | UPDATE of `readiness_score`, `readiness_band`, `readiness_computed_at` only |
| `pdei.evidence` | UPDATE of `status` only (ACTIVE → EXPIRING → EXPIRED), guarded on current status |
| `pdei.processed_events` | INSERT … ON CONFLICT DO NOTHING |

### 5.3 Metrics (PLATFORM-CONTRACT §13)

Recorded by `ReadinessEngine`, which is why `ReadinessWorkerConfig` builds it with the `MeterRegistry`
explicitly rather than leaving it to auto-configuration:

- `pdei_readiness_computation_seconds` (timer)
- `pdei_readiness_score{merchant}` (gauge)

Recorded by `ReadinessWorkerMetrics`:

- `pdei_events_processed_total{service,type,outcome}`
- `pdei_events_duplicate_total{service}`
- `pdei_event_processing_latency_seconds{service,type}`
- `pdei_readiness_recompute_coalesced_total`, `pdei_readiness_recompute_lock_contended_total`,
  `pdei_readiness_expiry_transitions_total{status}`, `pdei_readiness_at_risk_feed_size`,
  `pdei_readiness_pending_recomputes` (worker-local, not contract metrics)

## 6. How the debounce actually works

```
event ──► EventIntake ──► RecomputeDebouncer.submit
                              │
                              ├─ first event for TX?  schedule fire at now + debounce (2s)
                              └─ else                 merge request, slide the deadline,
                                                      but never past firstSeenAt + 30s
                              ▼
                         fire ──► worker pool ──► ReadinessRecomputeService.recompute
                                                       │
                                              SET NX pdei:lock:readiness:{TX}
                                                 ├─ acquired   → compute
                                                 ├─ held       → drop (deterministic: same answer)
                                                 └─ Redis down → compute anyway (fail open)
```

The lock's third state matters. `RedisLocks` returns `null` both when someone else holds the lock and
when Redis is unreachable, and the correct reaction is opposite: skip in the first case, proceed in
the second. `RecomputeLock` distinguishes them by probing the key, because degrading to *no readiness
at all* when a cache is down would be far worse than degrading to duplicated work.

## 7. Configuration

### 7.1 Environment variables (PLATFORM-CONTRACT §15)

| Variable | Default | Used for |
|---|---|---|
| `PDEI_POSTGRES_URL` | `jdbc:postgresql://postgres:5432/pdei` | datasource |
| `PDEI_POSTGRES_USER` / `PDEI_POSTGRES_PASSWORD` | `pdei` / `pdei` | datasource |
| `PDEI_KAFKA_BOOTSTRAP` | `kafka:9092` | consumers + producers |
| `PDEI_REDIS_URL` | `redis://redis:6379` | idempotency, cache, lock |
| `PDEI_READINESS_SWEEP_CRON` | `0 15 2 * * *` | expiry sweep schedule |
| `PDEI_FLYWAY_ENABLED` | `true` | set false when another service migrates |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | — | tracing |

### 7.2 `pdei.readiness.*` (this module)

| Property | Default | Meaning |
|---|---|---|
| `debounce` | `2s` | burst absorption window |
| `max-debounce-delay` | `30s` | ceiling on deferral under continuous traffic |
| `lock-ttl` / `lock-attempts` / `lock-backoff` | `30s` / `1` / `50ms` | `pdei:lock:readiness:{txId}` |
| `worker-threads` / `queue-capacity` | `4` / `10000` | recompute pool (caller-runs on overflow) |
| `idempotency-ttl` | `7d` | `pdei:idem:{eventId}` |
| `publish-gap-events` / `gap-event-min-severity` | `true` / `HIGH` | gap notification floor |
| `update-transaction-projection` | `true` | maintain `transactions.readiness_*` |
| `sweep.enabled` / `.cron` / `.zone` | `true` / `0 15 2 * * *` / `UTC` | expiry sweep |
| `sweep.batch-size` / `.max-batches` / `.warning-days` | `500` / `20` / `7` | expiry sweep |
| `sweep.recompute-affected` | `true` | rescore touched transactions |
| `at-risk.enabled` / `.interval` / `.initial-delay` | `true` / `5m` / `1m` | feed scan |
| `at-risk.limit` / `.stale-after` / `.bands` | `500` / `6h` / `AT_RISK,NOT_READY` | feed scan |

### 7.3 `pdei.core.*` (evidence-core, set by this module)

`readiness.expiring-soon-days=7`, `readiness.cache-ttl=10m`, `storage.ensure-buckets-on-startup=false`
(the worker never touches MinIO objects), `audit.publish-to-kafka=true`.

## 8. Dependencies on other modules

| Module | What is used |
|---|---|
| `platform-common` | `CanonicalEvent`, `AuditEvent`, `EventType`, `Topics`, `ConsumerGroups`, `EventHeaders`, `MetricNames`, `Ids`, `Json`, `Hashes`, `Clocks`, domain enums |
| `platform-persistence` | `ProcessedEventRepository`, the `pdei` schema and its Flyway migrations |
| `evidence-core` | `ReadinessEngine`, `GapDetector`, `ContradictionDetector`, `ReadinessDataProvider`, `PolicyEngine`, `EventPublisherPort`, `RedisLocks`, `ReadinessSnapshot`/`ReadinessGap`/`RequirementView`/`ContradictionView`/`EvidenceView`/`TransactionFacts` |

Depends on **no service module**. Communication with the rest of the platform is Kafka only.

### 8.1 Two deliberate auto-configuration overrides

`CoreAutoConfiguration` and `CorePersistenceAutoConfiguration` register every bean
`@ConditionalOnMissingBean` and document the substitution explicitly. This module substitutes two:

| Bean | Replaced by | Why |
|---|---|---|
| `ReadinessRepositoryPort` | `ReadinessStore` | the port signature cannot express `is_current`, `trigger_reason`, `trigger_event_id` |
| `ReadinessDataProvider` | `JdbcReadinessDataProvider` | four narrow queries written against the migration's real columns |

## 9. Build and run

```bash
# from repo root
cd backend
mvn -pl readiness-worker -am clean verify        # builds the 3 library modules + this one
mvn -pl readiness-worker spring-boot:run          # needs postgres, redis and kafka reachable

# container (context is backend/, not this directory)
docker build -f backend/readiness-worker/Dockerfile -t pdei/readiness-worker:dev backend
docker run --rm --network pdei-net -p 8084:8084 \
  -e PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
  -e PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
  -e PDEI_REDIS_URL=redis://redis:6379 \
  pdei/readiness-worker:dev
```

Verify:

```bash
curl -s localhost:8084/actuator/health
curl -s localhost:8084/actuator/prometheus | grep pdei_readiness
```

## 10. Extension points

- **New recomputation trigger** — add a member to `RecomputeTrigger` **and** to
  `ck_readiness_snapshots_trigger` in `V6__readiness.sql`. The enum's `precedence()` decides which
  trigger survives a merged burst. `EventRelevanceTest` asserts the two stay in step.
- **Manual recompute endpoint** — `POST /transactions/{id}/readiness/recompute` lives on
  api-gateway-service (§8.1). It can either write to Kafka or, if a direct path is ever wanted, call
  `ReadinessRecomputeService.recompute` with `RecomputeTrigger.MANUAL_RECOMPUTE`.
- **Policy version changes** — `RecomputeTrigger.POLICY_VERSION_CHANGE` exists and outranks evidence
  events, but nothing raises it yet (see gaps).
- **Different expiry rules** — implement `EvidenceExpiryStore`; `ExpirySweepJob` depends only on the
  interface.
- **Serving the at-risk feed from this worker** — `AtRiskScanner.feed()` / `feedFor(merchantId)` are
  already the shape `GET /api/v1/gaps` needs.
- **Alternative scoring** — do not fork the formula. `ReadinessEngine.score(ReadinessInput)` is a pure
  static function and is the single definition of PLATFORM-CONTRACT §7.

## 11. Known gaps and TODOs

1. **`evidence-core`'s JDBC adapters do not match the migrations.** `JdbcReadinessRepository`,
   `JdbcEvidenceRepository` and `JdbcAuditRepository` select columns (`id`, `penalty_points`,
   `requirements_json`, `gaps_json`, `parent_evidence_id`, `before_json`) that
   `V3`/`V6`/`V8` do not create (they use `evidence_id`, `snapshot_id`, `penalty_total`,
   `requirements`, `superseded_by`, `before_state`). This module works around it by supplying its own
   `ReadinessStore` and `JdbcReadinessDataProvider`, which are written against the real schema.
   **The fix belongs in `evidence-core`, not here**; when it lands, revisit whether these two
   overrides are still worth keeping (the `ReadinessStore` one is, for the trigger columns).
   The same divergence still affects any *other* consumer of `EvidenceRepositoryPort` in this JVM.
2. **`POLICY_VERSION_CHANGE` is never raised.** PLATFORM-CONTRACT §7 lists a policy version change as
   a recomputation trigger. There is no policy event type in §3.1 to consume, so the trigger enum
   member exists and is honoured but nothing produces it. Options: a `POLICY` canonical event type
   (a contract change), or a scan in `AtRiskScanner` comparing `policy_versions.effective_from`
   against `readiness_snapshots.computed_at`.
3. **Contradiction detail is lossy on read-back.** `V6` has no contradictions column. Full
   `ContradictionView` records are stored in `readiness_gaps.metadata` on write, but
   `ReadinessStore.findLatest` currently reconstructs only `left`, `detail`, `severity` and
   `detectedAt` from the columns. Reading the metadata JSON back would make it lossless.
4. **`base_score` is stored as INTEGER.** `ReadinessSnapshot.baseScore()` is a double; `V6` declares
   `base_score INTEGER`, so the persisted value is rounded. The authoritative score
   (`score`, also an integer) is unaffected; only the pre-penalty diagnostic loses its fraction.
5. **The at-risk feed is per replica and in memory.** Correct (every replica materialises the same
   query) but not shared, and empty until the first scan completes. If `GET /api/v1/gaps` is ever
   served from this worker rather than from `readiness_gaps` directly, that first-scan window needs
   handling.
6. **`AtRiskScanner.findStale` scans all transactions**, not only a merchant's. Fine at simulation
   scale; at real scale it wants a partial index on `readiness_computed_at` or per-merchant batching.
7. **No integration test against Postgres or Kafka.** The unit tests cover the two behaviours that
   carry real risk (debounce collapsing, expiry transitions), but `ReadinessStore` SQL is currently
   verified by reading, not by running. A Testcontainers test in the style of
   `platform-persistence`'s `AbstractPostgresIntegrationTest` is the obvious next step — it is also
   the only thing that would have caught gap 1.
8. **Kafka consumer lag** (`pdei_kafka_consumer_lag{group,topic}`) is not published by this module;
   it is expected from the broker-side exporter or a Micrometer Kafka binder that has not been wired.
9. **`ReadinessCache` writes but nothing here reads it.** The cache exists for api-gateway-service;
   the worker only fills and evicts. That is intentional, but it means a cache bug would be invisible
   from this service alone.
