# `platform-common` - the frozen shared API

> Implements **`docs/SHARED-LIBRARY-API.md` section 1** exactly. That document is the contract;
> this one explains the implementation. Where they disagree, the contract wins and this file is
> stale.

Maven coordinates: `com.laserpay.pdei:platform-common:0.1.0-SNAPSHOT`
Java package root: `com.laserpay.pdei.common`

---

## 1. Purpose

Every other JVM module in PDEI depends on this one, and none of them may redefine what it declares.
It exists so that "an event", "an amount of money", "an evidence id", "a readiness band" and "a
topic name" mean precisely one thing across nine services - and so that a change to any of those
meanings is a single, visible, reviewable edit rather than nine drifting copies.

Its API surface is **frozen** by `docs/SHARED-LIBRARY-API.md`. Adding a helper is fine; renaming,
re-typing or removing anything the contract names is a platform-wide breaking change.

## 2. Hard constraints

- **No Spring.** No `@Component`, no autoconfiguration, no `META-INF/spring/*.imports`. Services
  wire these types themselves. This keeps the jar usable from a plain `main()`, from tests, and
  from the simulator without dragging a context along.
- **No JPA.** Persistence types live in `platform-persistence`.
- **Dependencies:** `jackson-annotations`, `jackson-databind`, `jackson-datatype-jsr310`,
  `micrometer-core` (API only, no registry). Tests add JUnit 5 and AssertJ. That is the whole list.
- **No floating-point money, anywhere.**
- **No `LocalDateTime`, anywhere.** `Instant` only.

## 3. File-by-file map

```
src/main/java/com/laserpay/pdei/common/
├── money/
│   ├── Money.java                      record(long amountMinor, String currency), Comparable
│   └── CurrencyMismatchException.java   final RuntimeException, carries both currencies
├── id/
│   ├── IdPrefix.java                    MER- CUS- TX- PAY- ORD- SHP- DLV- REF- COM- EV- POL-
│   │                                    DSP- CASE- INV- AUD- SIM-  (+ ALL, prefixOf)
│   ├── Ids.java                         static factories + withSeed(long) / with(RandomGenerator)
│   └── SeededIdGenerator.java           instance generator over an injectable RandomGenerator
├── event/
│   ├── AggregateType.java               13 constants
│   ├── EventSource.java                 7 constants (PSP_ADAPTER … MERCHANT_PORTAL)
│   ├── EventType.java                   31 constants, each bound to an AggregateType; fromWire()
│   ├── CanonicalEvent.java              the envelope + Builder; partitionKey(); payloadAs()
│   ├── RawEventEnvelope.java            pre-normalisation source event
│   ├── DeadLetterEnvelope.java          pdei.dlq.v1 payload + from(...) / digestOf(Throwable)
│   ├── AuditEvent.java                  hash-chained audit record; computeHash/withHash/verify*
│   └── ActorType.java                   SYSTEM, MERCHANT_USER, OPERATOR, AI_SERVICE, SIMULATOR
├── domain/                              the 14 shared enums of PLATFORM-CONTRACT section 6
│   ├── EvidenceType.java                13 constants
│   ├── EvidenceStatus.java              6 constants + isUsable()/isTerminal()
│   ├── EvidenceSource.java              8 constants
│   ├── DisputeReasonCode.java           10 constants
│   ├── DisputeStatus.java               10 constants + isTerminal()/isResolved()
│   ├── CaseStatus.java                  9 constants + isTerminal()/isWaiting()
│   ├── ReadinessBand.java               4 bands + fromScore(int) + minScore/maxScore
│   ├── RequirementStrength.java         4 constants + weight() 3/2/1/0
│   ├── GapType.java                     7 constants
│   ├── GapSeverity.java                 4 constants + rank()
│   ├── InvestigationClassification.java 5 constants
│   ├── RecommendedAction.java           5 constants
│   ├── SafetyDecision.java              ALLOW / ALLOW_WITH_REVIEW / DENY
│   └── ChaosType.java                   13 constants + Category
├── kafka/
│   ├── Topics.java                      8 topic names, partition counts, forEventType routing
│   ├── ConsumerGroups.java              pdei-<service-name> constants + forService()
│   └── EventHeaders.java                7 header names + encode/decode
├── hash/
│   └── Hashes.java                      sha256(byte[]/InputStream/String), canonicalJsonSha256,
│                                        chain(previous, payload), GENESIS_HASH
├── json/
│   └── Json.java                        the shared ObjectMapper + canonical() for hashing
├── error/
│   ├── PdeiException.java               sealed root; code(), httpStatus(), details(), isRetryable()
│   ├── ValidationException.java         400  VALIDATION_ERROR
│   ├── PolicyViolationException.java    422  POLICY_VIOLATION
│   ├── EvidenceIntegrityException.java  409  EVIDENCE_INTEGRITY (+ hashMismatch factory)
│   ├── NotFoundException.java           404  NOT_FOUND
│   ├── ConflictException.java           409  CONFLICT (+ illegalTransition factory)
│   ├── UpstreamUnavailableException.java 503 UPSTREAM_UNAVAILABLE (the only retryable one)
│   ├── UnknownEventTypeException.java   400  UNKNOWN_EVENT_TYPE
│   └── ErrorResponse.java               wire shape for API errors
├── time/
│   ├── Clocks.java                      functional interface: now(); system()/fixed()/of()
│   └── TimeWindows.java                 withinDays, isExpired, isExpiringSoon, isDeadlineUrgent, …
└── metrics/
    └── MetricNames.java                 the 16 metric names of PLATFORM-CONTRACT section 13
                                         + Tag keys + Outcome values

src/test/java/com/laserpay/pdei/common/
├── money/MoneyTest.java                 arithmetic, overflow, mismatch, normalisation, display
├── hash/HashesTest.java                 known answers, streaming, chain determinism & tampering
├── json/JsonTest.java                   canonical key ordering, ISO-8601, NON_NULL, tolerance
├── domain/ReadinessBandTest.java        every band boundary + RequirementStrength weights
├── event/CanonicalEventTest.java        partitionKey, defaults, causation, JSON round trip
├── event/AuditEventTest.java            hash determinism, chain linkage, tamper detection
├── id/IdsTest.java                      prefixes, alphabet, seeded reproducibility
└── time/TimeWindowsTest.java            expiry / expiring-soon / deadline urgency boundaries
```

## 4. Design decisions worth knowing before you change anything

### 4.1 `Money`

Integer minor units plus an ISO-4217 code, exactly mirroring `amount_minor BIGINT` +
`currency CHAR(3)`. Arithmetic uses `Math.addExact`/`subtractExact`/`multiplyExact`, so a
financial overflow throws instead of silently wrapping. Mixing currencies throws
`CurrencyMismatchException` - PDEI performs no FX, so mixed currency is always a bug.

Currency codes are normalised to trimmed upper case in the compact constructor, so
`Money.of(10, "inr").equals(Money.of(10, "INR"))`. `toDisplayString()` is the only decimal
rendering in the platform, uses the ISO-4217 fraction digits (JPY 0, INR 2, KWD 3), falls back to 2
for codes the JVM does not know, and must never be parsed back.

`CurrencyMismatchException` extends `RuntimeException`, **not** `PdeiException` - the contract says
so, and it keeps the money package dependency-free.

### 4.2 `CanonicalEvent`

The compact constructor is deliberately *tolerant where it can be and strict where it must be*:

| Field | Behaviour when absent |
|---|---|
| `eventId`, `aggregateId`, `merchantId`, `occurredAt`, `eventType`, `source` | `ValidationException` - an event without these cannot be routed, deduped or audited |
| `schemaVersion` | defaults to `1` |
| `aggregateType` | derived from `eventType.aggregateType()` |
| `observedAt` | defaults to `occurredAt` |
| `correlationId` | defaults to `eventId` |
| `idempotencyKey` | defaults to `eventId` |
| `causationId` | blank normalises to `null` |
| `payload` | defaults to an empty object node |

`partitionKey()` returns `merchantId + ":" + aggregateId` and is the single definition of the Kafka
key. `Builder.causedBy(parent)` propagates `correlationId` and `merchantId` and records
`causationId`, which is what keeps the causal chain from a PSP webhook to a submitted representment
walkable.

`payloadFrom(Object)` is intentionally *not* an overload of `payload(JsonNode)` - an overload pair
would make `payload(null)` ambiguous at the call site.

### 4.3 `AuditEvent` and hashing

`computeHash()` is SHA-256 over the canonical JSON of every field **except `hash`**, per the
contract. Because `previousHash` is one of the hashed fields, the chain is intrinsic: editing any
historical record changes its hash and invalidates every link after it, which is exactly what
`GET /audit/verify-chain` detects. `withHash()` seals a record, `chainedAfter(previous)` links and
seals in one step, `verifyLink(previous)` checks both the link and the content.

`Hashes.chain(previousHash, payloadHash)` = `sha256(previous || payload)` with a null/blank previous
normalised to `GENESIS_HASH` (64 zeros). It is available for any other chain (evidence version
chains, package manifests) and is *not* used inside `AuditEvent.computeHash()` - see Known gaps.

**Do not change `Json.canonical()` casually.** Every stored audit hash in every environment was
computed with it; changing the canonical form invalidates them all.

### 4.4 `Json`

One configured `ObjectMapper`: `JavaTimeModule`, ISO-8601 (never epoch numbers), `NON_NULL`
inclusion, `FAIL_ON_UNKNOWN_PROPERTIES` off. Unknown-property tolerance is deliberate: a producer
deployed ahead of a consumer must not break it. `canonical(JsonNode)` sorts object keys at every
depth, preserves array order (array order is semantic), and emits no whitespace.

### 4.5 `Ids` / `SeededIdGenerator`

Ids are `PREFIX + 8` characters from Crockford base32 without `I L O U`, so they survive being read
aloud or copied out of a log. `Ids.withSeed(seed)` returns a generator whose entire output sequence
- including UUID-shaped `eventId()`s - is reproducible, which is what makes a simulator run
replayable byte for byte. The static methods draw from `ThreadLocalRandom` and are not reproducible
by design.

### 4.6 `PdeiException`

Sealed with exactly the seven permitted subclasses from the contract, all `final`, all in this
package (a sealed hierarchy in an unnamed module must be single-package). Each carries a stable
`code()`, an advisory `httpStatus()` so `api-gateway-service` needs no translation table, and an
immutable `details()` map. `isRetryable()` is true only for `UpstreamUnavailableException` - this is
what Temporal activity retry policies and Kafka retry/DLQ logic should branch on.
`ValidationException` and `PolicyViolationException` are the contract's non-retryable activity
failures (PLATFORM-CONTRACT section 10).

### 4.7 `Clocks`

A one-method interface, not `java.time.Clock`, because domain code needs only `now()` and because a
functional interface makes `Clocks.fixed(instant)` a one-liner in tests. Expiry transitions,
readiness penalties and deadline urgency all depend on "now"; none of them may call `Instant.now()`
directly.

## 5. Inbound contracts (what this module consumes)

None at runtime. `platform-common` reads no database, no topic, no HTTP route, no environment
variable and no config file. It is a pure library.

Its *inbound design* contracts are documents:
`docs/SHARED-LIBRARY-API.md` section 1 (the API surface) and
`docs/PLATFORM-CONTRACT.md` sections 3, 4, 5, 6, 7 and 13 (envelope, topics, ids, enums, readiness
bands, metric names).

## 6. Outbound contracts (what this module produces)

A jar, and through it these platform-wide invariants:

| Consumer | What it takes from here |
|---|---|
| every module | `Money`, `Ids`, `Json`, `Hashes`, `Clocks`, `PdeiException`, `MetricNames` |
| `ingestion-service`, `simulator-service` | `RawEventEnvelope`, `Topics.RAW_EVENTS`, `EventHeaders` |
| `normalization-worker` | `EventType.fromWire`, `CanonicalEvent.Builder`, `Topics.forEventType` |
| `state-builder-worker`, `readiness-worker`, `document-processor-service` | `CanonicalEvent`, `Topics.*`, `ConsumerGroups.*`, `DeadLetterEnvelope` |
| `case-orchestrator-service` | `CaseStatus`, `SafetyDecision`, `RecommendedAction`, `PdeiException` (retryability) |
| `audit-service` | `AuditEvent`, `Hashes.chain`, `Hashes.GENESIS_HASH`, `ActorType` |
| `evidence-core` | the whole `domain` package, `ReadinessBand.fromScore`, `RequirementStrength.weight` |
| `api-gateway-service` | `ErrorResponse`, `PdeiException.httpStatus()` |

Cross-language mirrors that must stay field-identical (contract section 4):
`CanonicalEvent` ↔ `pdei_ai.models.events.CanonicalEvent` ↔ `frontend/src/lib/types/events.ts`;
`Money` ↔ `pdei_ai.models.common.Money` ↔ `frontend/src/lib/types/common.ts`.
The 14 domain enums must be spelled identically in Python and TypeScript.

## 7. Configuration and environment variables

None. This module reads nothing from the environment. If you find yourself adding a `System.getenv`
here, the logic belongs in the consuming service instead.

## 8. Dependencies on other modules

None. `platform-common` is the root of the internal dependency graph and must stay there - it may
never depend on `platform-persistence`, `evidence-core`, or any service module.

## 9. Build and run

Not runnable; it is a library.

```bash
# from repo root
mvn -f backend/platform-common/pom.xml clean test      # unit tests
mvn -f backend/platform-common/pom.xml clean install   # install to the local repo

# from the reactor (once other modules exist)
cd backend && mvn -pl platform-common -am clean verify
```

Coverage report: `backend/platform-common/target/site/jacoco/index.html`.

## 10. Extension points

- **New event type:** add the constant to `EventType` with its `AggregateType` (and `internal` flag
  if PDEI produces it), update `docs/PLATFORM-CONTRACT.md` section 3.1, then the Python and
  TypeScript mirrors. `Topics.forEventType` routes automatically from the aggregate/type predicates.
- **New domain enum value:** add to the enum, update contract section 6, then the two mirrors.
  Consumers that `switch` exhaustively will fail to compile - that is the point.
- **New failure class:** you cannot add one without editing the `permits` clause of
  `PdeiException`, which is deliberate. Prefer reusing an existing code with richer `details()`.
- **New metric:** add the constant to `MetricNames` (and `ALL`) *and* to contract section 13; keep
  tag values a bounded vocabulary.
- **Alternative randomness:** `Ids.with(RandomGenerator)` accepts any generator, e.g. a
  `SecureRandom` or a jumpable `L64X128MixRandom` for parallel simulation streams.

## 11. Known gaps and TODOs

1. **Nothing here has been compiled or test-run.** The generating machine has no JDK and no Maven
   (`java`, `javac`, `mvn` all absent from `PATH`), so every source file and both POMs are
   unverified by a real build. **First task in a JDK 21 environment:**
   `mvn -f backend/platform-common/pom.xml clean test`.
2. **`AuditEvent.computeHash()` does not call `Hashes.chain()`.** The contract defines the audit
   hash as "sha256 over canonical JSON of all fields except `hash`", and since `previousHash` is one
   of those fields the chaining is intrinsic. `Hashes.chain()` is therefore a general-purpose
   primitive here rather than the audit chain's implementation. If audit-service prefers the
   explicit two-step form (`chain(previousHash, payloadHash)`), that is a contract clarification, not
   a local change - both sides must move together or stored hashes stop verifying.
3. **`micrometer-core` is a dependency but only `MetricNames` (plain strings) is implemented.** No
   registry helper, no `Timer`/`Counter` factory, no common tag set. Deliberate: the contract lists
   only `MetricNames`. If several services end up writing the same registration boilerplate, promote
   a helper here - and document it, since it widens the frozen surface.
4. **No JSON Schema generation.** `schemas/events/` is empty. The canonical envelope currently
   exists as a Java record, a Pydantic model and a TS interface with nothing mechanically checking
   they agree. Generating or validating against `schemas/events/canonical-event.schema.json` in CI
   is the obvious follow-up (contract section 4 already names the schemas as the referee).
5. **`Money.toDisplayString()` is ISO-4217-digit aware but not locale aware** - grouping is always
   `Locale.ROOT` comma groups, so it renders `INR 12,999.00` rather than the Indian lakh grouping
   `INR 12,999.00` → `₹12,999.00`. Display formatting for the merchant UI belongs in the frontend;
   this method is for logs and debugging.
6. **`RawEventEnvelope.partitionKey()` keys on `merchantId:idempotencyKey`,** which is a choice, not
   a contract requirement (PLATFORM-CONTRACT section 4 defines the key for canonical topics). It is
   now only a **fallback**: both producers on `pdei.raw.events.v1` (ingestion-service and
   simulator-service) key by `merchantId + ":" + aggregateId` and use this method solely when the
   aggregate id cannot be resolved, because normalization-worker consumes that topic with
   concurrency > 1 and needs per-aggregate ordering.
7. **`ConsumerGroups` constant naming** follows the shared-library note "`PDEI_` + service name"
   (`PDEI_NORMALIZATION_WORKER = "pdei-normalization-worker"`). The doc comment was terse; if the
   intended constant names were shorter (`NORMALIZATION_WORKER`), fix it here once and update every
   consumer, not the other way round.
8. **No `package-info.java`** files and therefore no package-level `@NonNullApi`-style nullness
   documentation. Worth adding with JSpecify if nullness checking is introduced later.
9. **Seeded id collision behaviour is untested beyond 10,000 draws.** The 8-character body is 40
   bits; a simulation generating tens of millions of ids per prefix would need a longer body.
