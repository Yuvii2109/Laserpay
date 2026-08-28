# simulator-service - module context

> Port **8088** · package `com.laserpay.pdei.simulator` · Spring Boot (web)
> Reload this file first when returning to this module. It is the complete picture of the
> synthetic world, the chaos engine, the replay path, and every place they are deliberately
> unfinished.

---

## 1. Purpose

Two jobs, and the platform's credibility rests on both.

**The synthetic financial world.** PDEI has no real merchants, so it generates them: merchants,
customers, catalogues, transactions, payments, orders, order lines, shipments, deliveries,
refunds, communications, evidence and disputes, spread across a configurable number of simulated
days. `(seed, WorldSpec)` fully determines the output down to the bytes of every event, which is
what makes a benchmark repeatable rather than anecdotal (reference §39.11).

**The chaos engine.** A distributed system's resilience claims are unfalsifiable until something
breaks it on purpose. This service injects every `ChaosType` in platform contract §6, records each
injection to `chaos_injections`, and announces it as a `CHAOS_INJECTED` notification the console
renders. Rules 9, 10 and 11 - tolerate duplicates, assume late and out-of-order events,
reproducible workloads - stop being assertions and become things you can watch happen.

It is the only service that deliberately breaks the others. Everything destructive is therefore
bounded or off by default.

---

## 2. Responsibilities

| # | Responsibility | Where |
|---|---|---|
| 1 | Deterministic world generation | `world.WorldGenerator` |
| 2 | Curated named scenarios with declared outcomes | `world.ScenarioLibrary`, `world.Scenario` |
| 3 | Synthetic evidence documents with real bytes and real hashes | `world.SyntheticArtifact`, `emit.ArtifactUploader` |
| 4 | Rate-limited, back-pressured publication to `pdei.raw.events.v1` | `emit.EventEmitter`, `emit.RateLimiter` |
| 5 | Run lifecycle and a progress model in Postgres + Redis | `emit.SimulationRunner`, `emit.RunProgressStore` |
| 6 | Live control of a running emission (stop + stream chaos) | `emit.EmissionControl` |
| 7 | Every `ChaosType` from contract §6 | `chaos.ChaosEngine` |
| 8 | Container-level chaos, with a documented fallback | `chaos.WorkerControl` |
| 9 | `CHAOS_INJECTED` notification | `chaos.ChaosNotifier` |
| 10 | Topic replay from an offset or a timestamp | `replay.ReplayService` |
| 11 | Serve contract §8.5 | `controller.SimulationController` |

---

## 3. File-by-file map

```
simulator-service/
├── pom.xml                       evidence-core + web + actuator + kafka-clients
├── Dockerfile                    multi-stage; build context is backend/
├── context.md                    this file
└── src/
    ├── main/java/com/laserpay/pdei/simulator/
    │   ├── SimulatorApplication.java           @SpringBootApplication entry point
    │   ├── config/
    │   │   ├── SimulatorProperties.java        `pdei.simulator.*` - emit/runs/artifacts/chaos/replay/cors
    │   │   ├── SimulatorConfiguration.java     Clocks, WorldGenerator, bounded run executor
    │   │   └── CorsConfig.java                 browser grant for /sim/** from the Next.js console
    │   ├── world/
    │   │   ├── WorldGenerator.java             THE generator; deterministic, no wall clock
    │   │   ├── WorldSpec.java                  seed, size, days, disputeRateBps, startAt, floors
    │   │   ├── FailureMix.java                 every "how broken" knob, in basis points
    │   │   ├── FailureProfile.java             CLEAN | REALISTIC | HOSTILE presets
    │   │   ├── GeneratedWorld.java             the stream + ids + counts + gross value
    │   │   ├── SimEvent.java                   one generated event + its RawEventEnvelope
    │   │   ├── SyntheticArtifact.java          the bytes behind a piece of evidence
    │   │   ├── SourceVocabulary.java           canonical EventType -> source system + event name
    │   │   ├── Catalogue.java                  name/product/city/carrier pools
    │   │   ├── Scenario.java                   a spec plus its DECLARED expected outcome
    │   │   ├── ScenarioLibrary.java            the ten curated scenarios
    │   │   └── AiPath.java                     DETERMINISTIC | AMBIGUOUS
    │   ├── emit/
    │   │   ├── EventEmitter.java               rate + backpressure + live chaos hooks
    │   │   ├── RateLimiter.java                token bucket
    │   │   ├── EmissionControl.java            stop + duplicate/drop/delay/reorder budgets
    │   │   ├── SimulationRunner.java           run lifecycle, concurrency cap, retained streams
    │   │   ├── RunProgress.java                the progress shape (Postgres + Redis + API)
    │   │   ├── RunProgressStore.java           simulation_runs + pdei:sim:run:{runId}
    │   │   └── ArtifactUploader.java           synthetic evidence bytes -> MinIO
    │   ├── chaos/
    │   │   ├── ChaosEngine.java                dispatch for all 13 ChaosTypes
    │   │   ├── ChaosRequest.java               {type, target, delayMs?, count?}
    │   │   ├── ChaosResult.java                what happened, and by which mechanism
    │   │   ├── WorkerControl.java              Docker API, else Redis control directive
    │   │   └── ChaosNotifier.java              CHAOS_INJECTED as a hash-chained audit event
    │   ├── replay/
    │   │   ├── ReplayService.java              assign + seek + poll, throwaway group
    │   │   ├── ReplayRequest.java              {topic, fromOffset|fromTimestamp, merchantId?}
    │   │   └── ReplayResult.java               offsets, counts, duration - the proof
    │   ├── controller/
    │   │   ├── SimulationController.java       contract §8.5
    │   │   ├── CreateRunRequestDto.java        POST /runs body
    │   │   ├── RunViewDto.java                 run + nested RunDetailDto
    │   │   ├── ChaosRequestDto.java            POST /chaos body
    │   │   ├── ChaosViewDto.java               GET /chaos row
    │   │   ├── ReplayRequestDto.java           POST /replay body
    │   │   └── ScenarioViewDto.java            GET /scenarios row, incl. expectations
    │   └── web/
    │       └── SimulatorExceptionHandler.java  → shared ErrorResponse shape
    ├── main/resources/application.yml          default / local / test profiles
    └── test/java/com/laserpay/pdei/simulator/world/
        ├── WorldGeneratorDeterminismTest.java  byte-identical streams for a fixed seed
        └── ScenarioLibraryTest.java            keys, seeds, expectations, generated content
```

---

## 4. The deterministic world

### 4.1 What makes it deterministic

Three rules, and breaking any one silently breaks reproducibility:

1. **One seeded generator, consumed strictly in order.** `new java.util.Random(spec.seed())` (a
   `RandomGenerator` whose algorithm the JDK specifies exactly, so the sequence is identical on
   every machine) plus `Ids.withSeed(seed)` for identifiers. No `ThreadLocalRandom`, no hash codes,
   no non-insertion iteration order.
2. **No wall clock.** Every timestamp is an offset from `WorldSpec.startAt`, which defaults to a
   fixed instant (`2026-01-05T06:00:00Z`) rather than "now". `Instant.now()` appears nowhere in
   `WorldGenerator`. Determinism holds for any fixed `startAt`, so a demo can move the world
   forward without giving it up.
3. **Insertion-ordered maps everywhere.** Event bodies are `LinkedHashMap`s. `Map.of` has a
   per-JVM salted iteration order and would make the serialised bytes differ between runs - this is
   why `WorldGenerator.orderedMap(...)` and `fields(...)` exist.

`WorldGeneratorDeterminismTest` asserts this at the byte level: it serialises every envelope and
compares the resulting bytes, which catches exactly the failure modes a count-based check sails
past.

### 4.2 What one transaction looks like

```
OrderCreated                      t0
PaymentCreated / Authorized        t0+5s / +17s
  └─ PaymentFailed + OrderCancelled on a paymentFailureBps hit, and the transaction ends
PaymentCaptured                    t0+41s
EvidenceAdded  PAYMENT_PROOF, INVOICE, ORDER_RECORD, AVS_CVV_RESULT
per shipment (1, or 2-3 on a multiShipmentBps hit):
  ShipmentCreated                  t0 + 4-20h (+12h per extra parcel)
  EvidenceAdded  SHIPPING_RECORD
  ShipmentDispatched               +4-20h
  CommunicationCreated             dispatch notice to the customer
  ShipmentDelivered                +20-96h        (skipped for one stranded parcel)
  EvidenceAdded  DELIVERY_PROOF                   (skipped on a missingDeliveryProofBps hit)
  EvidenceAdded  DELIVERY_PROOF dated BEFORE dispatch  (on a contradictoryDeliveryBps hit)
OrderFulfilled                     lastDelivered + 2h
CommunicationReceived + EvidenceAdded CUSTOMER_COMMUNICATION   (customerContactBps)
RefundCreated / RefundProcessed + EvidenceAdded REFUND_RECEIPT (refundBps, partialRefundBps)
DisputeCreated + EvidenceAdded PRIOR_TRANSACTION_HISTORY       (disputeRateBps)
```

Merchant-level, once per merchant: `MERCHANT_POLICY` and `TERMS_OF_SERVICE`, expiring either a
year out or five days *before* the world starts (`expiredEvidenceBps`).

### 4.3 Stream shaping, after generation

`sort by observedAt` → `applyOutOfOrder` (adjacent swaps) → `applyDuplicates` (verbatim
re-emission, same `rawEventId` and `idempotencyKey`) → `applyDrops` (evidence and communication
events only - dropping a `PaymentCaptured` would leave the ledger wrong, which is a different and
much less interesting failure than "the document nobody uploaded") → renumber.

Ordering by **observation** time rather than occurrence time is what makes a late arrival
genuinely late in the stream rather than merely labelled as such.

### 4.4 Contradictions the detector can actually find

`ContradictionDetector` works on `TransactionFacts` and keys off `deliveredAt < dispatchedAt` and
`deliveredAt < order.createdAt`. So the contradictory-delivery failure mode emits a second delivery
record dated **six hours before dispatch** - an impossible ordering - rather than two proofs that
merely disagree by a couple of days. Two artifacts with different dates would look wrong to a human
and be invisible to the deterministic engine, which would make the scenario a lie.

---

## 5. The ten curated scenarios

`GET /sim/v1/scenarios` · `POST /sim/v1/scenarios/{key}/run`

Every scenario pins its own seed, generates a single merchant, disputes **every** transaction with
a forced reason code, and fixes `startAt`, so the outcome is never a sampling accident. Each
declares its expected outcome in code, which turns it into an executable assertion about the
deterministic engine rather than a bag of fixtures.

Score ranges derive from contract §7 against `DefaultPolicyMatrix`. For `GOODS_NOT_RECEIVED` the
denominator is `5 mandatory x 3 + 0.5 x (2 recommended x 2) = 17`; dropping DELIVERY_PROOF removes
3 from the numerator, an expired MERCHANT_POLICY removes 3 *and* adds -10, each contradiction costs
a flat -15.

| # | key | seed | reason code | Expected band (score) | Expected gaps | AI path | Classification → action |
|---|---|---|---|---|---|---|---|
| 1 | `clean-delivery-defendable` | 4281 | GOODS_NOT_RECEIVED | **READY** (95-100) | none | DETERMINISTIC | DEFENDABLE → PREPARE_REPRESENTMENT |
| 2 | `missing-delivery-proof` | 9137 | GOODS_NOT_RECEIVED | **NEARLY_READY** (78-86) | MISSING | AMBIGUOUS | INSUFFICIENT_EVIDENCE → GATHER_MORE_EVIDENCE |
| 3 | `contradictory-delivery-dates` | 5507 | GOODS_NOT_RECEIVED | **NEARLY_READY** (80-89) | CONTRADICTORY | AMBIGUOUS | AMBIGUOUS → ESCALATE_TO_HUMAN |
| 4 | `expired-policy-evidence` | 7724 | GOODS_NOT_RECEIVED | **AT_RISK** (66-74) | EXPIRED | AMBIGUOUS | WEAK → REQUEST_POLICY_REVIEW |
| 5 | `duplicate-charge-dispute` | 3312 | DUPLICATE_PROCESSING | **READY** (90-95) | none | DETERMINISTIC | DEFENDABLE → PREPARE_REPRESENTMENT |
| 6 | `partial-refund-dispute` | 6180 | CREDIT_NOT_PROCESSED | **READY** (92-100) | none | DETERMINISTIC | DEFENDABLE → PREPARE_REPRESENTMENT |
| 7 | `multi-shipment-order` | 8846 | GOODS_NOT_RECEIVED | **READY** (92-100) | none | DETERMINISTIC | DEFENDABLE → PREPARE_REPRESENTMENT |
| 8 | `late-evidence-arrival` | 2059 | GOODS_NOT_RECEIVED | **READY** (95-100) *after settling* | none, once settled | DETERMINISTIC | DEFENDABLE → PREPARE_REPRESENTMENT |
| 9 | `subscription-cancelled-dispute` | 4472 | SUBSCRIPTION_CANCELLED | **NOT_READY** (22-40) | EXPIRED, MISSING | AMBIGUOUS | WEAK → ESCALATE_TO_HUMAN |
| 10 | `high-value-urgent-deadline` | 1174 | GOODS_NOT_RECEIVED | **NEARLY_READY** (78-86) | MISSING | AMBIGUOUS | INSUFFICIENT_EVIDENCE → ESCALATE_TO_HUMAN |

**What each one is actually for**

1. **clean-delivery-defendable** - the baseline. All MANDATORY satisfied, zero contradictions, so
   contract §9.4's deterministic short-circuit fires and **zero AI calls happen**.
2. **missing-delivery-proof** - the most common real loss. The carrier says delivered; nobody
   uploaded the signed proof. The gap is detected *before* a dispute exists, which is the product's
   entire pre-dispute premise.
3. **contradictory-delivery-dates** - every artifact is present, so a naive completeness check
   calls this ready. `deliveredAt < dispatchedAt` costs -15, and `maxContradictions = 0` means the
   safety gate will not auto-prepare.
4. **expired-policy-evidence** - present-but-expired, the failure a file-count dashboard cannot
   see: the requirement is unsatisfied *and* a -10 expiry penalty applies.
5. **duplicate-charge-dispute** - every event is emitted twice with an identical idempotency key.
   Run it, run it again: event counts double, readiness does not move. Rule 9, visible.
   REFUND_RECEIPT is RECOMMENDED and absent, which is why the score sits below 100.
6. **partial-refund-dispute** - a 30-70% refund is receipted and the customer disputes the
   remainder. All three MANDATORY artifacts for CREDIT_NOT_PROCESSED are present, so readiness
   resolves deterministically; the interesting part is downstream, where the narrative must
   reconcile refunded against disputed **minor units**.
7. **multi-shipment-order** - deliberately shows a **limitation**. Requirements are checked per
   evidence *type*, so one delivery proof satisfies DELIVERY_PROOF even though a parcel is still in
   transit. Per-line-item coverage is a known gap and this is where to demonstrate it honestly.
8. **late-evidence-arrival** - watch the *score*, not the end state. The proof is uploaded sixty
   days after delivery, well after the dispute opens, so readiness starts in the MISSING-gap band
   and climbs to READY when the late artifact lands. Rule 10, end to end.
9. **subscription-cancelled-dispute** - SUBSCRIPTION_CANCELLED needs TERMS_OF_SERVICE *and*
   CUSTOMER_COMMUNICATION as MANDATORY. Two expired mandatory documents cost both their weight and
   two -10 penalties; SIGNED_CONTRACT is never generated, so a MISSING gap appears on the
   RECOMMENDED side too.
10. **high-value-urgent-deadline** - same readiness and same gap as #2; the difference is entirely
    admission **priority** (`0.40 x financial impact + 0.25 x deadline urgency`, which is 1.0 inside
    48 hours). A 12,999.00 INR floor per transaction and a one-day deadline. **Override `startAt`
    on the run request** so the deadline is genuinely ahead of now, otherwise the past-deadline
    short-circuit escalates it without ever scoring it.

---

## 6. Inbound contracts

### 6.1 REST (contract §8.5) - base `http://localhost:8088/sim/v1`

```
POST /runs                   {seed, merchants, transactions, days, disputeRateBps, failureProfile,
                              currency?, startAt?, reasonCode?, requestedBy?}   -> 202 + runId
GET  /runs                   ?page&size
GET  /runs/{runId}           the run, UNWRAPPED, with live progress laid over its counters
POST /runs/{runId}/stop      cooperative stop
POST /chaos                  {type, target, delayMs?, count?, runId?, actor?}   -> ChaosResult
GET  /chaos                  ?runId&page&size
POST /replay                 {topic, fromOffset|fromTimestamp, merchantId?, maxRecords?, republish?}
GET  /scenarios              curated scenarios incl. their declared expectations
POST /scenarios/{key}/run    ?seed&startAt&requestedBy                          -> 202
GET  /actuator/health|prometheus|metrics|info|loggers
```

**CORS.** The console at `/simulation` (contract §14) reaches this service *directly* on port
8088 - it is beside the gateway, not behind it - so every one of those calls is cross-origin.
`config.CorsConfig` therefore grants `/sim/**` to the configured frontend origins with
`GET, POST, OPTIONS` and `allowedHeaders("*")` (the JSON posts send `Content-Type`,
`Idempotency-Key` and `X-Correlation-Id`, each of which forces a preflight). Credentials are not
granted: the client fetches with `credentials: 'same-origin'` and this service holds no session.
The mapping is scoped to `/sim/**`, so `/actuator/**` stays browser-unreachable.

### 6.2 Kafka consumed

None as a listener. `ReplayService` builds a **throwaway** `KafkaConsumer` per replay
(`pdei-simulator-service-replay-{uuid}`) using `assign` + `seek`, never `subscribe`, and never
commits - reading history must not disturb any live consumer group's position.

### 6.3 Tables read / written

| Table | Access |
|---|---|
| `pdei.simulation_runs` | insert + update (status, progress, counts, `params`, `stats`) |
| `pdei.chaos_injections` | insert (REQUESTED) then update (APPLIED/FAILED, `result`) |
| `pdei.evidence` | read for chaos targeting; **write** for EXPIRE_EVIDENCE and the DB-fallback of CORRUPT_EVIDENCE_HASH |
| `pdei.audit_events` | via evidence-core `AuditRecorder`, for the CHAOS_INJECTED notification |

### 6.4 Redis keys (contract §12)

```
pdei:sim:run:{runId}              run progress JSON, TTL 24h
pdei:stream:offsets:{group}       replay bookmark (a record, not a Kafka commit)
pdei:sim:control:{service}        chaos control directive - the documented Docker fallback
```

`pdei:sim:control:{service}` is **not** in contract §12's list; it is a simulator-local key under
the `pdei:` namespace, introduced for the fallback described in §8 below and recorded here so it is
not mistaken for a contract key.

---

## 7. Outbound contracts

### 7.1 `pdei.raw.events.v1` - `RawEventEnvelope`, source-shaped

Raw events carry the **source system's** vocabulary, not canonical `EventType` names; turning one
into the other is normalization-worker's entire job, and a simulator that published canonical
events directly would skip the layer it exists to exercise. `SourceVocabulary` is therefore the
mapping table normalization-worker must implement, expressed once as this module's outbound
contract:

| Source system | `sourceEventType` | Canonical `EventType` |
|---|---|---|
| `psp-adapter` | `payment_intent.created` / `.authorized` / `.succeeded` / `.payment_failed` | PaymentCreated / Authorized / Captured / Failed |
| `psp-adapter` | `refund.created` / `refund.succeeded` | RefundCreated / RefundProcessed |
| `psp-adapter` | `charge.dispute.created` / `.updated` / `.closed` | DisputeCreated / Updated / Closed |
| `order-system` | `order.created` / `order.fulfilled` / `order.cancelled` | OrderCreated / Fulfilled / Cancelled |
| `logistics` | `shipment.label_created` / `.in_transit` / `.delivered` | ShipmentCreated / Dispatched / Delivered |
| `crm` | `message.sent` / `message.received` | CommunicationCreated / Received |
| `merchant-portal` | `document.uploaded` / `.expired` / `.invalidated` | EvidenceAdded / Expired / Invalidated |

Envelope fields: `rawEventId` (deterministic UUID from the seeded generator), `sourceSystem`,
`sourceEventType`, `merchantId`, `receivedAt` = observation time, `idempotencyKey` =
`{sourceSystem}:{sourceEventType}:{aggregateId}:{occurredAtMillis}`, `headers`, `body`.
Partition key is `merchantId + ":" + aggregateId` (contract §4), produced by `SimEvent.partitionKey()`
and passed explicitly to `EventEmitter.toRecord`. It is the same scheme ingestion-service uses on
`pdei.raw.events.v1`, so the topic has one key scheme across both producers: every event about one
aggregate stays on one partition and normalization-worker (concurrency 3) cannot normalise two of
them out of order. Because the key is per-aggregate rather than per-fact, a duplicate still lands on
the same partition as its original - chaos re-publishes pass the aggregate id to
`EventEmitter.publish(envelope, aggregateId)` for that reason.

Headers: `pdei-event-type` (the canonical name, **a hint** - normalization-worker may derive it
from `sourceEventType` and ignore this, which is what it will do for real webhooks),
`pdei-merchant-id`, `pdei-correlation-id`, `pdei-schema-version`, plus module-local
`pdei-sim-seed` and `pdei-sim-occurred-at`.

Money in every body is `{"amountMinor": 1299900, "currency": "INR"}`. Never a decimal, anywhere,
including in a "rate" - `FailureMix` and `disputeRateBps` are integer basis points for exactly this
reason.

### 7.2 Evidence artifacts in MinIO

Bucket `pdei-evidence`, key layout per contract §11, user metadata `sha256` / `evidence-id` /
`version`. The bytes are plain text rendered deterministically from the artifact's own fields, so
the same seed produces the same bytes and therefore the same sha256. Plain text rather than
generated PDFs on purpose: PDF writers embed timestamps and object ids that would break
byte-reproducibility, and PDF extraction is already proven by document-processor-service's own
tests against generated PDFs.

Without this, "evidence" in a simulated world is a row pointing at nothing, and the two things PDEI
claims to do - verify an artifact's hash and search its text - cannot be demonstrated at all.

### 7.3 `pdei.evidence.events.v1`

`EvidenceExpired` (via evidence-core's `EventPublisherPort`) when `EXPIRE_EVIDENCE` chaos fires, so
readiness recomputes with the -10 mandatory-expiry penalty.

### 7.4 `pdei.audit.events.v1` - the `CHAOS_INJECTED` notification

The WebSocket frame the frontend renders is `{type, at, merchantId, data}` with
`type = "CHAOS_INJECTED"` (contract §8.1), and **api-gateway-service** is the component that pushes
it. The simulator therefore does not open a socket of its own. It records each injection as a
hash-chained audit event on a topic every service already produces to and audit-service already
consumes:

```
entityType = "CHAOS_INJECTION",  entityId = injectionId,
action     = "CHAOS_INJECTED",   actorType = SIMULATOR,
after      = the frame's `data` object
```

api-gateway wraps that into the frame. Two things fall out of the choice: chaos becomes part of the
permanent tamper-evident record rather than a transient UI event - "which failure was injected, and
when" is exactly what an observer needs to trust a recovery demo - and no new topic, key namespace
or endpoint is invented for it. See the gaps section for what api-gateway still has to do.

### 7.5 Metrics

```
pdei_chaos_injections_total{type,status}     contract §13
pdei_sim_events_emitted_total{topic}         module-local
pdei_sim_events_failed_total                 module-local
pdei_sim_events_dropped_total                module-local
pdei_sim_replayed_records_total{topic}       module-local
```

---

## 8. Chaos: all 13 types

Every injection writes a `chaos_injections` row **before** it acts, so a failed injection is as
visible as a successful one. That is the difference between chaos engineering and an unexplained
outage.

| ChaosType | What it does | Claim under test |
|---|---|---|
| `DUPLICATE_EVENT` | Sets a budget on the live emission; with no run in flight, re-publishes retained traffic verbatim | consumers dedupe on `eventId` (rule 9) |
| `DELAYED_EVENT` | Delays the next *n* events by `delayMs` | late arrival converges (rule 10) |
| `OUT_OF_ORDER_EVENT` | Holds an event back so its successor overtakes it | ordering is not assumed (rule 10) |
| `DROP_EVENT` | Never publishes the next *n* events | a missing artifact becomes a detected gap |
| `DELETE_EVIDENCE` | Deletes the MinIO object, leaves the row claiming it exists | integrity catches a vanished object |
| `CORRUPT_EVIDENCE_HASH` | Overwrites the stored bytes, leaving `evidence.sha256` describing content that is gone | a tampered artifact fails its check (rule 8) |
| `EXPIRE_EVIDENCE` | Backdates `expiresAt`, sets EXPIRED, publishes `EvidenceExpired` | expiry moves readiness, and it recomputes |
| `CONFLICTING_EVIDENCE` | Publishes a delivery proof dated before dispatch, **as a raw event** | contradictions are found, not averaged over |
| `KILL_WORKER` | Docker `kill`, else a control directive | Temporal recovers the workflow |
| `RESTART_CONSUMER` | Docker `restart`, else a control directive | rebalance redelivers, and that is survivable |
| `SLOW_CONSUMER` | Docker `pause` + `unpause`, else a control directive | lag is visible and bounded, not silent |
| `REPLAY_EVENTS` | Delegates to `ReplayService` | history re-consumable without damage (rule 11) |
| `INJECT_DISPUTE` | Publishes a `charge.dispute.created` raw event on an existing transaction | the case pipeline starts from a cold dispute |

**Target selectors** (`chaos_injections.target`, JSONB, stored verbatim):

```
DUPLICATE / DELAYED / OUT_OF_ORDER / DROP        runId
DELETE / CORRUPT / EXPIRE evidence               evidenceId | transactionId
CONFLICTING_EVIDENCE                             transactionId
KILL_WORKER / RESTART_CONSUMER / SLOW_CONSUMER   service
REPLAY_EVENTS                                    topic, fromOffset | fromTimestamp, merchantId?
INJECT_DISPUTE                                   transactionId, merchantId?, reasonCode?
```

**Why `CONFLICTING_EVIDENCE` and `INJECT_DISPUTE` go through raw events** rather than direct row
inserts: the injected fact then arrives through the normal ingestion path, and every stage
downstream - normalisation, state building, gap detection - has to handle it exactly as it would a
real conflicting source.

**Container control and its documented fallback.** `KILL_WORKER`, `RESTART_CONSUMER` and
`SLOW_CONSUMER` use the Docker Engine API over plain HTTP when
`pdei.simulator.chaos.docker-enabled` is true - a real killed process, so Temporal genuinely has to
recover and the consumer group genuinely has to rebalance. It is **off by default** because
exposing the Docker socket is equivalent to handing out root on the host. When it is off or the
call fails, `WorkerControl` falls back to `MODE_REDIS_CONTROL_DIRECTIVE`: the instruction is written
to `pdei:sim:control:{service}` with a TTL, and the injection record says plainly which mechanism
ran. Nothing is faked - a chaos history never claims a container was killed when it was not. The
fallback's limitation is real and listed in the gaps below.

---

## 9. Configuration and environment

`pdei.simulator.*` (see `SimulatorProperties`):

| Group | Property | Default | Why |
|---|---|---|---|
| emit | `events-per-second` | 200 (`PDEI_SIM_EVENTS_PER_SECOND`) | Unthrottled runs show a queue draining, not a system working |
| emit | `max-in-flight` | 500 | Backpressure ceiling; a slow broker slows the run, not the heap |
| emit | `progress-update-every` | 250 | Progress flush cadence, in events |
| emit | `topic` | `pdei.raw.events.v1` | |
| emit | `send-timeout` | 30s | |
| runs | `max-concurrent` | 2 | Two runs on one broker interleave and spoil each other's numbers |
| runs | `redis-ttl` | 24h | `pdei:sim:run:{runId}` |
| runs | `retain-stream` / `retained-stream-limit` | true / 5000 | Lets chaos target a finished run's traffic |
| artifacts | `upload` / `max-uploads` | true (`PDEI_SIM_UPLOAD_ARTIFACTS`) / 5000 | Real bytes in MinIO |
| chaos | `docker-enabled` | **false** (`PDEI_SIM_DOCKER_CHAOS`) | Root-equivalent; explicit opt-in only |
| chaos | `docker-host` | `http://localhost:2375` (`PDEI_SIM_DOCKER_HOST`) | |
| chaos | `container-prefix` | `pdei-` | `readiness-worker` → `pdei-readiness-worker` |
| chaos | `control-key-prefix` / `control-ttl` | `pdei:sim:control:` / 5m | The fallback |
| chaos | `recent-event-buffer` | 2000 | Traffic kept per run for duplication |
| chaos | `max-event-count` / `max-delay` | 500 / 5m | A demo cannot wedge the executor for an hour |
| replay | `max-records` / `poll-timeout` / `republish` | 50000 / 2s / true | |
| cors | `allowed-origins` | `http://localhost:3000` (`PDEI_FRONTEND_ORIGIN`) | The console calls 8088 cross-origin |
| cors | `allowed-methods` / `allow-credentials` / `max-age` | `[GET, POST, OPTIONS]` / false / 1h | Only what §8.5 uses; no session to carry |

Environment (contract §15): `PDEI_POSTGRES_URL`, `PDEI_POSTGRES_USER`, `PDEI_POSTGRES_PASSWORD`,
`PDEI_KAFKA_BOOTSTRAP`, `PDEI_REDIS_URL`, `PDEI_MINIO_ENDPOINT`, `PDEI_MINIO_ACCESS_KEY`,
`PDEI_MINIO_SECRET_KEY`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`. Module-local:
`PDEI_FLYWAY_ENABLED`, `PDEI_SIM_EVENTS_PER_SECOND`, `PDEI_SIM_UPLOAD_ARTIFACTS`,
`PDEI_SIM_DOCKER_CHAOS`, `PDEI_SIM_DOCKER_HOST`, `PDEI_FRONTEND_ORIGIN` (same name and
default as api-gateway-service).

Profiles: `default` (compose hostnames) · `local` (localhost, 50 events/s, DEBUG) · `test` (no
uploads, no Docker chaos, unlimited rate, JDBC ports off).

`pdei.core.jdbc.enabled` is **true** here - that is what supplies the `AuditRecorder` behind the
`CHAOS_INJECTED` notification.

**Kafka producer note.** The value serializer is `JsonSerializer`, not `StringSerializer`, because
both `EventEmitter` and evidence-core's `KafkaEventPublisher` hand the template an object (a
`RawEventEnvelope`, a `CanonicalEvent`), never a pre-serialised string. `ReplayService` therefore
re-publishes a parsed `JsonNode` rather than the raw string, or the record would go back out as a
JSON-encoded string literal. `spring.json.add.type.headers` is off: the envelope is a cross-language
wire contract and a Java class name in a header is noise plus coupling.

---

## 10. Dependencies on other modules

| Module | What is used |
|---|---|
| `evidence-core` | `storage.ObjectStore` + `Buckets`, `spi.EventPublisherPort`, `audit.AuditRecorder` + `AuditCommand`, `config.CoreProperties` |
| `platform-persistence` | `SimulationRunEntity`, `ChaosInjectionEntity`, `EvidenceEntity` and their repositories, plus the autoconfigured scan |
| `platform-common` | `Ids`/`SeededIdGenerator`, `Money`, `RawEventEnvelope`, `CanonicalEvent`, `EventType`, `AggregateType`, `EventSource`, `Topics`, `ConsumerGroups`, `EventHeaders`, `Json`, `Hashes`, `Clocks`, `MetricNames`, all `domain.*` enums, `ErrorResponse` |

Runtime: PostgreSQL, Kafka, Redis (optional - progress degrades to Postgres only), MinIO
(optional - artifacts are skipped, events still flow), Docker Engine API (optional, opt-in).

**Downstream expectations.** normalization-worker must implement the `SourceVocabulary` mapping;
api-gateway-service must forward the `CHAOS_INJECTED` audit event as a WebSocket frame.

---

## 11. Build and run

```bash
# from Laserpay/backend
mvn -pl simulator-service -am clean verify        # build + determinism/scenario tests
mvn -pl simulator-service spring-boot:run          # needs Postgres/Kafka/Redis/MinIO

SPRING_PROFILES_ACTIVE=local mvn -pl simulator-service spring-boot:run

docker build -f simulator-service/Dockerfile -t pdei/simulator-service .
docker run --rm --network pdei-net -p 8088:8088 pdei/simulator-service
```

A demo, end to end:

```bash
# 1. the curated scenarios, with their declared expectations
curl -s localhost:8088/sim/v1/scenarios | jq '.[] | {key, expected}'

# 2. run one; note the runId
curl -s -XPOST 'localhost:8088/sim/v1/scenarios/clean-delivery-defendable/run' | jq .

# 3. watch progress
curl -s localhost:8088/sim/v1/runs/SIM-XXXXXXXX | jq .progress

# 4. an ad-hoc world
curl -s -XPOST localhost:8088/sim/v1/runs -H 'content-type: application/json' \
  -d '{"seed":4281,"merchants":3,"transactions":500,"days":30,"disputeRateBps":250,"failureProfile":"REALISTIC"}' | jq .

# 5. prove idempotency: duplicate the next 50 events of the live run
curl -s -XPOST localhost:8088/sim/v1/chaos -H 'content-type: application/json' \
  -d '{"type":"DUPLICATE_EVENT","count":50}' | jq .

# 6. prove replayability
curl -s -XPOST localhost:8088/sim/v1/replay -H 'content-type: application/json' \
  -d '{"topic":"pdei.raw.events.v1","fromOffset":0,"maxRecords":1000}' | jq .

# 7. break an artifact, then re-verify it
curl -s -XPOST localhost:8088/sim/v1/chaos -H 'content-type: application/json' \
  -d '{"type":"CORRUPT_EVIDENCE_HASH","target":{"transactionId":"TX-XXXXXXXX"}}' | jq .

# 8. the history
curl -s localhost:8088/sim/v1/chaos | jq '.[] | {type, status, mode: .result.mode, summary: .result.summary}'
```

---

## 12. Extension points

- **A new failure mode in the generated world** - add a bps knob to `FailureMix` (plus its wither),
  read it in `WorldGenerator`, and pin it to `FULL_BPS` in a scenario.
- **A new scenario** - add a `Scenario` to `ScenarioLibrary.build()` with a fresh seed. The test
  enforces distinct seeds, complete expectations, and band/score agreement.
- **A new chaos type** - add the constant to `ChaosType` in `platform-common` (a contract change),
  then a branch in `ChaosEngine.dispatch`; the switch is exhaustive, so the compiler finds the gap.
- **Real container orchestration** - `WorkerControl` isolates the Docker API; a Kubernetes
  implementation slots in behind the same `ControlOutcome`.
- **Richer artifacts** - `SyntheticArtifact.render(...)` produces the bytes; a PDF renderer would
  slot in there (see gaps for why it is text today).
- **Other currencies / locales** - `WorldSpec.currency` and `Catalogue`'s pools.
- **Direct canonical emission** (bypassing normalisation, for isolating downstream benchmarks) -
  would be a new emitter targeting `Topics.CANONICAL_EVENTS`; deliberately not built, because
  skipping normalisation hides the layer this service exists to exercise.

---

## 13. Known gaps and TODOs

1. **The `pdei:sim:control:{service}` fallback is a durable, auditable *request*, not an executed
   action.** No other PDEI service reads that key yet. With `docker-enabled: false` (the default),
   `KILL_WORKER` / `RESTART_CONSUMER` / `SLOW_CONSUMER` record the directive and report
   `mode = REDIS_CONTROL_DIRECTIVE` - honestly, but nothing dies. **To make these real, either
   enable Docker control or add a control-key listener to the worker services.** This is the single
   biggest gap in the module.
2. **`CHAOS_INJECTED` reaches the UI only if api-gateway-service forwards it.** The simulator emits
   the audit event; api-gateway must consume `pdei.audit.events.v1`, filter
   `action == "CHAOS_INJECTED"`, and push `{type, at, merchantId, data}` on the control-tower
   socket. Contract §4 does not currently list api-gateway as a consumer of the audit topic.
3. **Scenario expectations are declared, not yet verified end to end.** `ScenarioLibraryTest`
   checks internal consistency (band matches score range, seeds distinct, generated content matches
   the description). Asserting that a *running platform* lands on the declared band and AI path
   needs readiness-worker and case-orchestrator-service, and belongs in an integration test that
   does not exist yet. The stated ranges carry a few points of slack for the same reason.
4. **Synthetic artifacts are plain text, not PDFs.** Deterministic bytes matter more than realistic
   ones here (PDF writers embed timestamps and object ids that break byte-reproducibility), and
   document-processor-service already proves PDF extraction against generated PDFs in its own
   tests. A deterministic PDF renderer would be a genuine improvement for demo screenshots.
5. **Merchant-level evidence is keyed under `{merchantId}/{merchantId}/…` in MinIO**, because the
   contract §11 key layout requires a transaction segment and policy documents have no transaction.
   It is consistent and parseable, but it is a convention this module invented.
6. **`chaos_injections.merchant_id` has a foreign key to `merchants`.** The engine only sets it when
   the caller names one, and never invents one - but a caller passing a merchant id that is not in
   the database will fail on that constraint.
7. **Retained event streams are in-memory, per-instance and capped** at
   `retained-stream-limit` (5000). After a restart, `DUPLICATE_EVENT` against a finished run has
   nothing to re-publish and says so.
8. **`OUT_OF_ORDER_EVENT` holds at most one event at a time.** Deeper reordering (a three-way
   permutation) would need a small hold buffer in `EventEmitter`.
9. **Replay is synchronous and blocks the HTTP thread.** Bounded by `max-records` (50k) and by two
   empty polls, but a 50k replay on a slow broker will hold a request open. An async replay with a
   handle would match how runs work.
10. **`ReplayService` filters by merchant on the `pdei-merchant-id` header and falls back to a
    substring scan of the body** when the header is absent. The fallback can theoretically match a
    merchant id appearing elsewhere in the payload.
11. **Determinism is guaranteed for a fixed `(seed, startAt)` pair, on the same JDK major version.**
    `java.util.Random`'s algorithm is specified, so this holds across machines; the id alphabet and
    the payload field order are also fixed. It is *not* guaranteed across changes to
    `WorldGenerator` itself - any edit to the draw order changes every generated world, which is
    correct but worth knowing before comparing benchmarks across commits.
12. **No test covers `ChaosEngine`, `EventEmitter` or `ReplayService`.** They need a broker and a
    database; Testcontainers (already in the reactor's dependency management) is the intended route.
13. **`SimulationRunner` keeps runs in memory only while they execute.** A restart mid-run leaves
    the `simulation_runs` row in RUNNING forever - there is no startup reconciliation sweep that
    marks orphaned runs FAILED.
14. **The `partial-refund-dispute` and `multi-shipment-order` scenarios both resolve
    deterministically**, which is the honest outcome of the current requirement matrix but makes
    them less interesting as AI demos. Both are documented above as showing what the deterministic
    engine can and cannot see; #7 in particular is a deliberate demonstration of a real limitation
    (type-level rather than line-item requirement checking).
