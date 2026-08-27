# `backend/audit-service` — Immutable Audit Trail

> Module context. Normative sources, in precedence order:
> `docs/PLATFORM-CONTRACT.md` → `docs/SHARED-LIBRARY-API.md` → `planner/pre-dispute-evidence-intelligence-reference.md`.
> Where this file and those disagree, those win and this file is stale.

| Item | Value |
|---|---|
| Maven artifactId | `audit-service` |
| Java package root | `com.laserpay.pdei.audit` |
| Port | **8087** (host and container) |
| Kind | Spring Boot worker + web |
| Consumer group | `pdei-audit-service` |
| REST base | `http://localhost:8087/audit/v1` |
| Health | `GET /actuator/health` |
| Metrics | `GET /actuator/prometheus` |

---

## 1. Purpose

The tamper-evident record of everything the platform did.

Every other service *reports* what it changed by publishing an `AuditEvent`; every fact the platform
learns arrives as a `CanonicalEvent`. This service is the single writer that turns both into an
append-only, hash-chained history, and the only place that can answer the question a dispute
ultimately turns on: **has this history been altered?**

Three guarantees define it:

1. **Append-only.** No update path, no delete path, at any layer. `V8__audit.sql` additionally
   installs `trg_audit_events_immutable`, which rejects UPDATE and DELETE at the database — so a
   stray `psql` session cannot rewrite history either.
2. **Chained.** Each row stores the hash of its predecessor and its own hash covers that link, so
   altering any historical row invalidates every hash after it. **One chain per merchant**: a
   merchant's history verifies without reading anyone else's, and one noisy merchant cannot
   serialise the whole platform.
3. **Verifiable.** `GET /audit/v1/chain/verify` recomputes a chain and reports the first divergence
   with the audit id, the index, the expected hash and the actual hash.

Nothing here is probabilistic and nothing here calls a model (non-negotiable rules 1, 2, 8).

## 2. Responsibilities

1. Consume `pdei.audit.events.v1` — explicit audit reports, with producer-supplied
   `before`/`after` state.
2. Consume every domain topic — canonical, evidence, readiness, dispute, case — and derive an audit
   entry from each fact, so the trail is complete rather than merely diligent.
3. Append hash-chained rows to `pdei.audit_events`, idempotently, one chain per merchant.
4. Verify chains on demand and report the first divergence precisely.
5. Serve the read API of PLATFORM-CONTRACT §8.4, including a streamed NDJSON export.
6. Evaluate retention on a schedule — and, by default, delete nothing.

## 3. File map

```
backend/audit-service/
├── pom.xml
├── Dockerfile                       build context is backend/, not this directory
├── context.md                       this file
└── src/
    ├── main/java/com/laserpay/pdei/audit/
    │   ├── AuditServiceApplication.java   @SpringBootApplication + @EnableScheduling
    │   ├── config/
    │   │   ├── AuditProperties.java        pdei.audit.* (lock, consume, api, retention)
    │   │   ├── AuditServiceConfig.java      bean wiring; takes over AuditRepositoryPort
    │   │   └── KafkaConfig.java             two payload types, two container factories
    │   ├── repository/
    │   │   ├── AuditEventStore.java         append-only port (extends AuditRepositoryPort)
    │   │   ├── JdbcAuditEventStore.java     V8-accurate implementation
    │   │   └── AuditQuery.java              filter for /events and /export
    │   ├── chain/
    │   │   ├── AuditChainAppender.java      the single writer: validate, dedupe, lock, seal, retry
    │   │   ├── ChainVerifier.java           paged recomputation, first divergence
    │   │   ├── ChainDivergence.java         auditId, index, expectedHash, actualHash, kind
    │   │   └── ChainVerificationReport.java  per-chain result (+ toCoreVerification)
    │   ├── consume/
    │   │   ├── AuditEventConsumer.java      @KafkaListener pdei.audit.events.v1
    │   │   ├── DomainEventConsumer.java     @KafkaListener every domain topic
    │   │   ├── AuditIntake.java             shared: claim → map → append
    │   │   ├── CanonicalAuditMapper.java    CanonicalEvent -> AuditEvent, deterministically
    │   │   ├── IdempotencyGuard.java        Redis SETNX + processed_events claim
    │   │   └── DeadLetterPublisher.java     pdei.dlq.v1
    │   ├── controller/
    │   │   ├── AuditController.java         GET /events, /chain/verify, /export (NDJSON)
    │   │   ├── AuditEventResponse.java      field-identical to AuditEvent, so clients can re-hash
    │   │   ├── AuditPageResponse.java
    │   │   └── ChainVerifyResponse.java
    │   ├── retention/
    │   │   ├── RetentionPolicy.java         documented, scheduled, NON-DESTRUCTIVE by default
    │   │   └── RetentionReport.java
    │   └── metrics/
    │       └── AuditMetrics.java            contract §13 names + chain-specific counters
    ├── main/resources/application.yml
    └── test/java/com/laserpay/pdei/audit/
        ├── chain/ChainVerifierTest.java          tampered row, deleted row, re-sealing
        ├── chain/InMemoryAuditEventStore.java    store double enforcing V8's unique link index
        └── consume/CanonicalAuditMapperTest.java  total, deterministic, storable mapping
```

## 4. Inbound contracts

### 4.1 Kafka topics consumed

| Topic | Payload | Treatment |
|---|---|---|
| `pdei.audit.events.v1` | `AuditEvent` | stored as reported (subject to re-sealing) |
| `pdei.canonical.events.v1` | `CanonicalEvent` | derived via `CanonicalAuditMapper` |
| `pdei.evidence.events.v1` | `CanonicalEvent` | derived |
| `pdei.readiness.events.v1` | `CanonicalEvent` | derived |
| `pdei.dispute.events.v1` | `CanonicalEvent` | derived |
| `pdei.case.events.v1` | `CanonicalEvent` | derived |

The domain topic list is the bean `auditDomainTopics`, computed from `pdei.audit.consume.*`, so a
targeted replay can narrow it during an investigation.

**Overlap is intentional.** A service that both reports an audit entry *and* publishes a domain event
produces two entries for one change. They are not duplicates: one records the intent with
before/after state, the other records the fact, and they carry different audit ids and different
content. A trail with only one of them answers a different question than the one an auditor asks.

Idempotency is defended twice:

1. `IdempotencyGuard` — Redis `SETNX pdei:idem:{eventId}` (TTL 7d) in front of
   `ProcessedEventRepository.markProcessed(eventId, "pdei-audit-service")`;
2. `AuditChainAppender` — `store.exists(auditId)` plus `ON CONFLICT (audit_id) DO NOTHING`.

The second layer is not redundant. A derived audit id is a pure function of the event id, so it is
stable across a full topic replay months later — long after `processed_events` may have been pruned.

### 4.2 Tables read/written

| Table | Operation |
|---|---|
| `pdei.audit_events` | INSERT only (plus reads for verification, the API and retention reporting) |
| `pdei.processed_events` | INSERT … ON CONFLICT DO NOTHING |

### 4.3 Redis keys

| Key | Purpose | TTL |
|---|---|---|
| `pdei:idem:{eventId}` | consumer dedupe fast path | 7 days |
| `pdei:lock:audit:{merchantId}` | keeps one merchant chain linear across replicas | 30 seconds |

No key outside PLATFORM-CONTRACT §12 is introduced.

## 5. Outbound contracts

### 5.1 REST (PLATFORM-CONTRACT §8.4)

```
GET /audit/v1/events        ?entityType&entityId&merchantId&actor&action&from&to&page&size
GET /audit/v1/chain/verify  ?merchantId&maxChains
GET /audit/v1/export        ?entityType&entityId&merchantId&actor&action&from&to&limit
```

- `/events` — newest first, paged, `total` included. Default page size 50, max 500.
- `/chain/verify` — one chain, or every chain when `merchantId` is omitted. **Always returns 200**,
  even for a broken chain: a broken chain is a successful answer to the question that was asked, and
  a 500 would make monitoring report an outage of the audit service instead of the far more serious
  fact it just found.
- `/export` — `application/x-ndjson`, one entry per line, streamed with `StreamingResponseBody` over
  keyset batches. Peak memory is one batch whether the client asked for ten entries or a million.
  NDJSON rather than a JSON array so a client can process line by line, stop early, and still have
  whole valid records if the transfer is cut.

`AuditEventResponse` is **field-identical** to `AuditEvent`, deliberately: a client can recompute the
hash from the response and verify the chain itself without trusting this service. Renaming a field
would silently break that, because the hash is taken over the canonical JSON of those exact names.

There is **no POST, PUT, PATCH or DELETE** mapping in this service, and no write method behind one.

### 5.2 Kafka produced

| Topic | When |
|---|---|
| `pdei.dlq.v1` | a record cannot legally be stored, or retries were exhausted |

The service deliberately does **not** republish audit records: it is the sink, and echoing what it
consumes would be a loop (`pdei.core.audit.publish-to-kafka=false`).

### 5.3 Metrics

Contract §13 (via `MetricNames`): `pdei_events_processed_total{service,type,outcome}`,
`pdei_events_duplicate_total{service}`, `pdei_event_processing_latency_seconds{service,type}`.

Audit-specific: `pdei_audit_entries_appended_total{type,sealed}`,
`pdei_audit_chain_conflicts_total`, `pdei_audit_chain_verifications_total{result}`,
`pdei_audit_chain_verification_seconds`, `pdei_audit_entries_rejected_total{reason}`,
`pdei_audit_last_export_size`, `pdei_audit_broken_chains`.

**`pdei_audit_chain_verifications_total{result="broken"}` is the metric that matters.** It should be
flat at zero forever; an alert on any increment is the entire point of building a hash chain.

## 6. How the chain actually works

```
AuditEvent ──► validate (V8 check constraints)   ──► reject ⇒ pdei.dlq.v1
           ──► exists(auditId)?                   ──► yes    ⇒ no-op
           ──► SET NX pdei:lock:audit:{merchant}
           ──► head = lastHash(merchant) ?? GENESIS_HASH
                   ├─ producer already sealed against head, hash verifies ⇒ store verbatim
                   └─ otherwise ⇒ previousHash := head, recompute hash
           ──► INSERT
                   └─ unique (merchant_id, previous_hash) violated ⇒ re-read head, seal again
```

**The lock is an optimisation; the unique index is the correctness mechanism.** `ux_audit_events_link`
means two writers cannot both claim the same predecessor. The loser gets a constraint violation,
which the appender treats as "re-read the head and try again" rather than as an error. This is why
Kafka listener concurrency is **1**: more threads means more conflicts and more retries, with no more
throughput, because a merchant's chain is inherently serial. Scale with replicas, not threads.

**Re-sealing changes a hash a producer published.** That is intended. The stored chain is
authoritative; a producer's hash is a self-integrity seal on its own report, not a claim about this
chain's shape. The `auditId` — the identity of the fact — never changes.

### 6.1 Two representations of "genesis"

`AuditEvent` normalises a null predecessor to `Hashes.GENESIS_HASH` (sixty-four zeros) and hashes
*that*. `V8__audit.sql` stores genesis as `previous_hash IS NULL`, because
`ux_audit_events_genesis` is a partial unique index that gives each merchant exactly one first entry.
`JdbcAuditEventStore` therefore translates in both directions: `GENESIS_HASH → NULL` on write,
`NULL → GENESIS_HASH` on read. Getting this wrong in either direction makes every merchant's first
entry fail verification, which is why it has its own named methods and a comment.

### 6.2 Verification

Two independent checks per entry, walked in `sequence_no` order and stopped at the first failure:

| Check | Failure means |
|---|---|
| `previousHash` equals the hash of the preceding entry | `BROKEN_LINK` — a row was deleted, inserted, or the chain forked |
| recomputed content hash equals the stored `hash` | `TAMPERED_CONTENT` — a stored row was edited |

Both are needed: content alone misses a deletion (every surviving row still hashes correctly), link
alone misses an edit.

## 7. Configuration

### 7.1 Environment variables (PLATFORM-CONTRACT §15)

| Variable | Default | Used for |
|---|---|---|
| `PDEI_POSTGRES_URL` | `jdbc:postgresql://postgres:5432/pdei` | datasource |
| `PDEI_POSTGRES_USER` / `PDEI_POSTGRES_PASSWORD` | `pdei` / `pdei` | datasource |
| `PDEI_KAFKA_BOOTSTRAP` | `kafka:9092` | consumers + DLQ producer |
| `PDEI_REDIS_URL` | `redis://redis:6379` | idempotency, chain lock |
| `PDEI_AUDIT_RETENTION_CRON` | `0 45 3 * * *` | retention evaluation schedule |
| `PDEI_FLYWAY_ENABLED` | `true` | set false when another service migrates |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | — | tracing |

### 7.2 `pdei.audit.*` (this module)

| Property | Default | Meaning |
|---|---|---|
| `chain-lock-ttl` / `-attempts` / `-backoff` | `30s` / `5` / `50ms` | `pdei:lock:audit:{merchantId}` |
| `append-max-attempts` | `5` | retries after a chain-link conflict |
| `idempotency-ttl` | `7d` | `pdei:idem:{eventId}` |
| `accept-presealed-links` | `true` | keep a producer's hash when it verifies and links to our head |
| `consume.audit-topic` … `consume.case-events` | `true` | per-topic switches for targeted replay |
| `api.default-page-size` / `.max-page-size` | `50` / `500` | `/events` |
| `api.max-verify-events` | `100000` | bound on one `/chain/verify` walk |
| `api.export-batch-size` / `.max-export-events` | `500` / `1000000` | `/export` |
| `retention.enabled` / `.dry-run` | `false` / `true` | **leave these alone**; see §11 |
| `retention.retain-days` | `2555` (7 years) | retention floor |
| `retention.report-cron` / `.zone` | `0 45 3 * * *` / `UTC` | evaluation schedule |

### 7.3 `pdei.core.*` (evidence-core, set by this module)

`audit.publish-to-kafka=false` (this service is the sink), `storage.ensure-buckets-on-startup=false`
(it stores no objects).

## 8. Dependencies on other modules

| Module | What is used |
|---|---|
| `platform-common` | `AuditEvent` (and its `computeHash`/`withHash`/`verifyHash`), `CanonicalEvent`, `ActorType`, `AggregateType`, `EventType`, `EventSource`, `Topics`, `ConsumerGroups`, `EventHeaders`, `Hashes`, `Ids`, `Json`, `Clocks`, `MetricNames`, `ValidationException` |
| `platform-persistence` | `ProcessedEventRepository`, the `pdei` schema and its Flyway migrations |
| `evidence-core` | `AuditRepositoryPort` (extended by `AuditEventStore`), `ChainVerification`, `RedisLocks` |

Depends on **no service module**. Every inbound path is Kafka; every outbound path is HTTP responses
plus the DLQ.

`AuditServiceConfig` registers `JdbcAuditEventStore` as the `AuditEventStore`, which — because that
interface extends `AuditRepositoryPort` — also takes over that role and makes
`CorePersistenceAutoConfiguration` back off. That is the intended arrangement: the service that owns
the table owns the code that writes it.

## 9. Build and run

```bash
# from repo root
cd backend
mvn -pl audit-service -am clean verify
mvn -pl audit-service spring-boot:run          # needs postgres, redis and kafka reachable

# container (context is backend/, not this directory)
docker build -f backend/audit-service/Dockerfile -t pdei/audit-service:dev backend
docker run --rm --network pdei-net -p 8087:8087 \
  -e PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
  -e PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
  -e PDEI_REDIS_URL=redis://redis:6379 \
  pdei/audit-service:dev
```

Verify:

```bash
curl -s localhost:8087/actuator/health
curl -s 'localhost:8087/audit/v1/events?entityType=EVIDENCE&size=5' | jq .
curl -s 'localhost:8087/audit/v1/chain/verify?merchantId=MER-0001' | jq .
curl -s 'localhost:8087/audit/v1/export?merchantId=MER-0001&limit=100' | head -3
```

Prove the chain works end to end — this is the demo worth showing:

```bash
# 1. verify: intact
curl -s 'localhost:8087/audit/v1/chain/verify?merchantId=MER-0001' | jq .intact
# 2. tamper (requires dropping trg_audit_events_immutable first - by design it is hard)
# 3. verify again: intact=false, with the audit id, index and both hashes
```

## 10. Extension points

- **New audit source** — publish an `AuditEvent` to `pdei.audit.events.v1` using
  `evidence-core`'s `AuditRecorder`, or simply publish a canonical event and let
  `CanonicalAuditMapper` derive the entry.
- **Richer derived entries** — `CanonicalAuditMapper.afterState` decides what an auditor sees for a
  derived record. Adding a field there changes future hashes only; existing entries keep verifying.
- **Alternative storage** — implement `AuditEventStore`. `ChainVerifier`, `AuditChainAppender`,
  `AuditController` and `RetentionPolicy` depend only on the interface, which is how the whole chain
  is unit-tested without a database.
- **Signed checkpoints / archival** — see the design sketched on `RetentionPolicy`; the NDJSON export
  already emits exactly the artifact a checkpoint would archive.
- **A scheduled verification sweep** — `ChainVerifier.verifyAll` exists and is what such a job would
  call; nothing schedules it yet (see gaps).

## 11. Known gaps and TODOs

1. **`evidence-core`'s `JdbcAuditRepository` does not match `V8__audit.sql`.** It selects
   `id`, `before_json` and `after_json`; the migration creates `audit_id`, `before_state` and
   `after_state`, and it has no notion of `sequence_no`. Any service using that adapter to write
   audit rows will fail at runtime. This module works around it by owning `JdbcAuditEventStore` and
   registering it as the port. **The fix belongs in `evidence-core`.**
2. **`AuditRecorder.GENESIS` is the literal string `"GENESIS"`**, while `AuditEvent` and
   `Hashes.GENESIS_HASH` use sixty-four zeros. Entries sealed by `AuditRecorder` therefore hash over
   a different genesis marker than entries sealed here. This service re-seals such entries on
   arrival, so the stored chain is consistent — but the two constants should be reconciled in
   `evidence-core`, and until they are, a producer's genesis-linked hash will never be preserved.
3. **No scheduled chain verification.** Verification happens only when someone calls the endpoint.
   A nightly job calling `ChainVerifier.verifyAll` and alerting on
   `pdei_audit_chain_verifications_total{result="broken"}` is the obvious next step, and is what
   would make tampering detectable without a human asking.
4. **Retention deletes nothing, on purpose.** `RetentionPolicy` reports; it does not prune, and
   `trg_audit_events_immutable` would reject a delete anyway. Real retention needs the
   checkpoint-and-archive design documented on that class, which is a schema change.
5. **No integration test against Postgres or Kafka.** The chain logic is covered thoroughly against
   an in-memory store that enforces V8's unique link index, but `JdbcAuditEventStore`'s SQL is
   currently verified by reading, not by running — it is the one thing that would catch gap 1
   automatically. A Testcontainers test in the style of `platform-persistence`'s
   `AbstractPostgresIntegrationTest` is the next step.
6. **The export has no authentication.** Neither does any other service in this stack yet; the
   contract's `X-PDEI-Service-Token` covers only the AI tool callbacks. An audit export is the most
   sensitive read in the platform and should be the first endpoint to get a real check.
7. **`/chain/verify` with no `merchantId` walks every chain synchronously.** Bounded by `maxChains`
   and `api.max-verify-events`, but on a large database it is still a long request. It wants to be an
   async job with a result id rather than a blocking call.
8. **Ordering across aggregates is arbitrary.** Entries are appended in the order this consumer sees
   them, which for one aggregate is production order (partitions are keyed
   `merchantId + ":" + aggregateId`) but across a merchant's aggregates is not. This is why
   `occurred_at` is stored separately from chain position: the chain proves *that* nothing was
   altered, `occurred_at` says *when* it happened. Anything reconstructing a timeline must sort by
   `occurred_at`, not by `sequence_no`.
9. **`pdei_kafka_consumer_lag{group,topic}`** is not published by this module; it is expected from
   the broker-side exporter or a Micrometer Kafka binder that has not been wired.
