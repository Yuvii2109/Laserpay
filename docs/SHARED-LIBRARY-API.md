# Shared Library API (NORMATIVE)

Companion to `docs/PLATFORM-CONTRACT.md`. Declares the **exact** types that the shared
Maven modules expose. Service modules MUST consume these names verbatim and MUST NOT
redefine them locally.

---

## 1. `platform-common` (artifactId `platform-common`, package `com.laserpay.pdei.common`)

No Spring Boot autoconfiguration, no JPA. Plain Java 21 + Jackson + Micrometer API.

### 1.1 `…common.money`

```java
public record Money(long amountMinor, String currency) implements Comparable<Money> {
    public static Money of(long amountMinor, String currency);
    public static Money zero(String currency);
    public Money plus(Money other);          // throws CurrencyMismatchException
    public Money minus(Money other);
    public Money multiply(long factor);
    public boolean isZero();
    public boolean isPositive();
    public String toDisplayString();          // e.g. "INR 12,999.00" — display ONLY
}
public final class CurrencyMismatchException extends RuntimeException {}
```

### 1.2 `…common.id`

```java
public final class Ids {
    public static String merchant();      // MER-XXXXXXXX
    public static String customer();      // CUS-…
    public static String transaction();   // TX-…
    public static String payment();       // PAY-…
    public static String order();         // ORD-…
    public static String shipment();      // SHP-…
    public static String delivery();      // DLV-…
    public static String refund();        // REF-…
    public static String communication(); // COM-…
    public static String evidence();      // EV-…
    public static String policy();        // POL-…
    public static String dispute();       // DSP-…
    public static String disputeCase();   // CASE-…
    public static String investigation(); // INV-…
    public static String audit();         // AUD-…
    public static String simulation();    // SIM-…
    public static String eventId();       // UUID string
    public static String withPrefix(String prefix);
    public static boolean hasPrefix(String id, String prefix);
}
public final class IdPrefix {   // String constants: MERCHANT="MER-", … 
}
```

`Ids` accepts an injectable `java.util.random.RandomGenerator` via
`Ids.withSeed(long)` returning a `SeededIdGenerator` so the simulator is reproducible.

### 1.3 `…common.event`

```java
public enum AggregateType { MERCHANT, CUSTOMER, TRANSACTION, PAYMENT, ORDER, SHIPMENT,
                            DELIVERY, REFUND, COMMUNICATION, EVIDENCE, POLICY, DISPUTE, CASE }

public enum EventSource { PSP_ADAPTER, ORDER_SYSTEM, LOGISTICS, CRM, SIMULATOR,
                          INTERNAL, MERCHANT_PORTAL }

public enum EventType {
    PaymentCreated(AggregateType.PAYMENT), PaymentAuthorized(…), PaymentCaptured(…), PaymentFailed(…),
    OrderCreated(…), OrderFulfilled(…), OrderCancelled(…),
    ShipmentCreated(…), ShipmentDispatched(…), ShipmentDelivered(…),
    RefundCreated(…), RefundProcessed(…),
    CommunicationCreated(…), CommunicationReceived(…),
    EvidenceAdded(…), EvidenceExpired(…), EvidenceInvalidated(…),
    DisputeCreated(…), DisputeUpdated(…), DisputeClosed(…),
    ReadinessRecomputed(…), ReadinessGapDetected(…),
    CaseOpened(…), CaseEvidenceAttached(…), CaseInvestigated(…), CasePrepared(…),
    CaseEscalated(…), CaseSubmitted(…), CaseClosed(…),
    AuditRecorded(…);
    public AggregateType aggregateType();
    public boolean isEvidenceEvent(); public boolean isDisputeEvent();
    public boolean isCaseEvent();     public boolean isReadinessEvent();
    public static EventType fromWire(String s);   // exact-name match, throws UnknownEventTypeException
}

public record CanonicalEvent(
    String eventId, EventType eventType, int schemaVersion,
    AggregateType aggregateType, String aggregateId, String merchantId,
    String correlationId, String causationId,
    Instant occurredAt, Instant observedAt,
    EventSource source, String idempotencyKey,
    JsonNode payload
) {
    public String partitionKey();                 // merchantId + ":" + aggregateId
    public <T> T payloadAs(Class<T> type, ObjectMapper mapper);
    public static Builder builder();
}

public record RawEventEnvelope(
    String rawEventId, String sourceSystem, String sourceEventType,
    String merchantId, Instant receivedAt, String idempotencyKey,
    Map<String,String> headers, JsonNode body
) {}

public record DeadLetterEnvelope(
    String originalTopic, int partition, long offset, String consumerGroup,
    String failureClass, String failureMessage, String stackTraceDigest,
    Instant failedAt, int attempt, JsonNode originalPayload
) {}

public record AuditEvent(
    String auditId, String entityType, String entityId, String merchantId,
    String action, String actor, ActorType actorType, Instant occurredAt,
    String correlationId, JsonNode before, JsonNode after,
    String previousHash, String hash
) {
    public String computeHash();   // sha256 over canonical JSON of all fields except `hash`
}
public enum ActorType { SYSTEM, MERCHANT_USER, OPERATOR, AI_SERVICE, SIMULATOR }
```

### 1.4 `…common.domain` (enums shared everywhere — see contract §6)

`EvidenceType`, `EvidenceStatus`, `EvidenceSource`, `DisputeReasonCode`, `DisputeStatus`,
`CaseStatus`, `ReadinessBand`, `RequirementStrength`, `GapType`, `GapSeverity`,
`InvestigationClassification`, `RecommendedAction`, `SafetyDecision`, `ChaosType`.

`ReadinessBand` exposes `public static ReadinessBand fromScore(int score)`.
`RequirementStrength` exposes `public int weight()` (3/2/1/0).

### 1.5 `…common.kafka`

```java
public final class Topics {
    public static final String RAW_EVENTS       = "pdei.raw.events.v1";
    public static final String CANONICAL_EVENTS = "pdei.canonical.events.v1";
    public static final String EVIDENCE_EVENTS  = "pdei.evidence.events.v1";
    public static final String READINESS_EVENTS = "pdei.readiness.events.v1";
    public static final String DISPUTE_EVENTS   = "pdei.dispute.events.v1";
    public static final String CASE_EVENTS      = "pdei.case.events.v1";
    public static final String AUDIT_EVENTS     = "pdei.audit.events.v1";
    public static final String DLQ              = "pdei.dlq.v1";
}
public final class ConsumerGroups { /* PDEI_ + service name constants */ }
public final class EventHeaders {
    public static final String EVENT_ID = "pdei-event-id";
    public static final String EVENT_TYPE = "pdei-event-type";
    public static final String MERCHANT_ID = "pdei-merchant-id";
    public static final String CORRELATION_ID = "pdei-correlation-id";
    public static final String SCHEMA_VERSION = "pdei-schema-version";
    public static final String TRACEPARENT = "traceparent";
    public static final String ATTEMPT = "pdei-attempt";
}
```

### 1.6 `…common.hash`

```java
public final class Hashes {
    public static String sha256(byte[] data);
    public static String sha256(InputStream in) throws IOException;
    public static String sha256Hex(String s);
    public static String canonicalJsonSha256(Object o, ObjectMapper mapper);
    public static String chain(String previousHash, String payloadHash);
}
```

### 1.7 `…common.json`

```java
public final class Json {
    public static ObjectMapper mapper();      // JavaTimeModule, ISO-8601, no timestamps,
                                              // NON_NULL inclusion, fail-on-unknown = false
    public static String write(Object o);
    public static <T> T read(String s, Class<T> t);
    public static JsonNode tree(Object o);
    public static String canonical(JsonNode node);  // sorted keys, stable for hashing
}
```

### 1.8 `…common.error`

```java
public sealed class PdeiException extends RuntimeException permits
    ValidationException, PolicyViolationException, EvidenceIntegrityException,
    NotFoundException, ConflictException, UpstreamUnavailableException, UnknownEventTypeException {}
public record ErrorResponse(String code, String message, String correlationId,
                            Instant at, Map<String,Object> details) {}
```

### 1.9 `…common.time`

```java
public interface Clocks { Instant now(); static Clocks system(); static Clocks fixed(Instant i); }
public final class TimeWindows { public static boolean withinDays(Instant a, Instant b, int days); }
```

### 1.10 `…common.metrics`

```java
public final class MetricNames { /* String constants exactly matching contract §13 */ }
```

---

## 2. `platform-persistence` (package `com.laserpay.pdei.persistence`)

Spring Data JPA entities, repositories, Flyway migrations. Depends on `platform-common`.

Entities (`…persistence.entity`), all with `@Id String id`, `Instant createdAt/updatedAt`,
`@Version long version` where mutable:

```
MerchantEntity, CustomerEntity, ProcessedEventEntity,
TransactionEntity, PaymentEntity, OrderEntity, OrderLineEntity, RefundEntity,
ShipmentEntity, DeliveryEntity, CommunicationEntity,
EvidenceEntity, EvidenceVersionEntity, EvidenceRelationshipEntity,
PolicyEntity, PolicyVersionEntity, EvidenceRequirementEntity,
DisputeEntity, DisputeCaseEntity, CaseEvidenceEntity,
ReadinessSnapshotEntity, ReadinessGapEntity,
InvestigationEntity, InvestigationFindingEntity, AiAdmissionLogEntity,
AuditEventEntity, SimulationRunEntity, ChaosInjectionEntity
```

Money is mapped as two columns via `@Embeddable MoneyEmbeddable(long amountMinor, String currency)`
with converters to/from `Money`.

Repositories (`…persistence.repository`) — Spring Data interfaces named `<Entity>Repository`,
e.g. `EvidenceRepository extends JpaRepository<EvidenceEntity, String>` plus derived queries:

```java
List<EvidenceEntity> findByTransactionIdAndStatusIn(String txId, Collection<EvidenceStatus> s);
List<EvidenceEntity> findByMerchantIdAndTypeAndStatus(...);
Optional<EvidenceEntity> findByShaAndTransactionId(String sha256, String txId);
@Query(nativeQuery = true) List<EvidenceEntity> search(String tsQuery, String merchantId, Pageable p);
```

`ProcessedEventRepository.markProcessed(eventId, consumerGroup)` uses
`INSERT … ON CONFLICT DO NOTHING` and returns `boolean firstTime` — this is the
canonical Postgres-side idempotency primitive.

`…persistence.config.PersistenceAutoConfiguration` registers entity scan + repository scan
so service modules only need the dependency, registered via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

---

## 3. `evidence-core` (package `com.laserpay.pdei.core`)

The deterministic domain engine. Library (not a Spring Boot app). Depends on
`platform-common` + `platform-persistence`. Beans registered through
`CoreAutoConfiguration`.

```
…core.evidence.EvidenceService          create/version/expire/invalidate/link evidence
…core.evidence.EvidenceIntegrityService verify sha256 vs MinIO object, mark tampered
…core.evidence.EvidenceGraphService     build node/edge graph for a transaction
…core.evidence.EvidenceLineageService   version chain + provenance walk
…core.storage.ObjectStore               interface: put/get/presign/stat/delete
…core.storage.MinioObjectStore          implementation
…core.readiness.ReadinessEngine         compute(txId, reasonCode?) -> ReadinessSnapshot
…core.readiness.GapDetector             missing/expired/expiring/contradictory detection
…core.readiness.ContradictionDetector   cross-evidence field conflicts
…core.policy.PolicyEngine               applicablePolicy(merchantId, reasonCode),
                                        requirements(reasonCode), isActionPermitted(...)
…core.policy.PolicyVersionService       immutable versioning
…core.safety.AiResultValidator          contract §9.3 — returns SafetyDecision + reasons
…core.safety.SafetyGate                 combines validator + policy + thresholds
…core.ai.AiReasoningClient              interface  (investigate / narrative / admissionScore)
…core.ai.HttpAiReasoningClient          calls ai-reasoning-service over HTTP
…core.ai.AdmissionController            deterministic priority scoring + Redis budget
…core.dispute.DisputeService            dispute lifecycle
…core.dispute.CaseAssemblyService       assemble case evidence set + package manifest
…core.timeline.TimelineService          unified event/evidence timeline
…core.audit.AuditRecorder               append hash-chained audit events (+Kafka publish)
…core.search.EvidenceSearchService      Postgres FTS
…core.model.*                           immutable DTOs/records used across services:
    ReadinessSnapshot, ReadinessGap, EvidenceView, EvidenceGraph, EvidenceNode, EvidenceEdge,
    TimelineEntry, RequirementView, ContradictionView, InvestigationContext,
    InvestigationResult, SafetyVerdict, CaseXRay, PackageManifest, FunnelMetrics
```

`InvestigationContext` / `InvestigationResult` Java records mirror contract §9.1/§9.2 exactly
(camelCase field names identical to the JSON).

---

## 4. Cross-language type parity

The same logical types exist three times and MUST stay field-identical:

| Concept | Java | Python | TypeScript |
|---|---|---|---|
| Canonical event | `common.event.CanonicalEvent` | `pdei_ai.models.events.CanonicalEvent` | `frontend/src/lib/types/events.ts` |
| Money | `common.money.Money` | `pdei_ai.models.common.Money` | `types/common.ts` |
| InvestigationContext | `core.model.InvestigationContext` | `pdei_ai.models.investigation.InvestigationContext` | `types/ai.ts` |
| InvestigationResult | `core.model.InvestigationResult` | `pdei_ai.models.investigation.InvestigationResult` | `types/ai.ts` |
| ReadinessSnapshot | `core.model.ReadinessSnapshot` | — | `types/readiness.ts` |
| CaseXRay | `core.model.CaseXRay` | — | `types/case.ts` |

JSON Schemas in `/schemas` are the referee when the three disagree.
