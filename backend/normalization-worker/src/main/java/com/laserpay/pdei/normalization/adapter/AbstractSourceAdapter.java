package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.normalization.support.Payloads;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared machinery for every {@link SourceAdapter}: alias matching, source-vocabulary lookup,
 * deterministic identifier derivation and envelope assembly.
 *
 * <p>Subclasses supply two things - a mapping from the source's event names to {@link EventType},
 * and a method that turns the source body into the canonical payload. Everything that must be
 * identical across adapters (id derivation, correlation propagation, lateness preservation) lives
 * here so it cannot drift per source system.
 */
public abstract class AbstractSourceAdapter implements SourceAdapter {

    /**
     * Namespace for deterministic canonical event ids. Any stable UUID works; it is fixed here so
     * that a rebuild of this module cannot change the ids it derives.
     */
    private static final String EVENT_ID_NAMESPACE = "pdei.normalization.v1";

    private final String sourceSystem;
    private final Set<String> aliases;
    private final Set<String> normalizedAliases;
    private final Map<String, EventType> eventTypes;
    private final String defaultCurrency;

    /**
     * @param eventTypeMappings source event name to canonical type; keys are matched
     *                          case-insensitively with {@code -}, {@code _}, {@code /} and spaces
     *                          removed, so {@code orders/create} and {@code ORDER_CREATE} both hit
     *                          the same entry
     * @param defaultCurrency   ISO-4217 code used when a source omits the currency on a monetary
     *                          field; a documented deterministic fallback, not a guess per event
     */
    protected AbstractSourceAdapter(String sourceSystem,
                                    Set<String> aliases,
                                    Map<String, EventType> eventTypeMappings,
                                    String defaultCurrency) {
        this.sourceSystem = sourceSystem;
        this.aliases = Set.copyOf(aliases);
        Set<String> normalized = new LinkedHashSet<>();
        normalized.add(normalizeKey(sourceSystem));
        for (String alias : aliases) {
            normalized.add(normalizeKey(alias));
        }
        this.normalizedAliases = Collections.unmodifiableSet(normalized);
        Map<String, EventType> types = new LinkedHashMap<>();
        eventTypeMappings.forEach((key, value) -> types.put(normalizeKey(key), value));
        this.eventTypes = Collections.unmodifiableMap(types);
        this.defaultCurrency = defaultCurrency == null || defaultCurrency.isBlank()
                ? "INR" : defaultCurrency.toUpperCase(Locale.ROOT);
    }

    @Override
    public String sourceSystem() {
        return sourceSystem;
    }

    @Override
    public Set<String> aliases() {
        return aliases;
    }

    @Override
    public boolean supports(RawEventEnvelope raw) {
        return raw != null && normalizedAliases.contains(normalizeKey(raw.sourceSystem()));
    }

    /** The source event vocabulary this adapter understands, for {@code GET /schemas} style listings. */
    public Set<String> supportedSourceEventTypes() {
        return eventTypes.keySet();
    }

    protected String defaultCurrency() {
        return defaultCurrency;
    }

    // --- template method ------------------------------------------------------------------------

    @Override
    public CanonicalEvent normalize(RawEventEnvelope raw, Instant observedAt) {
        EventType eventType = resolveEventType(raw);
        return map(raw, eventType, observedAt == null ? raw.receivedAt() : observedAt);
    }

    /**
     * Builds the canonical event for an already-resolved type. Implementations read the source body,
     * assemble the canonical payload and finish with {@link #envelope}.
     */
    protected abstract CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt);

    /**
     * Resolves the source's own event name to a canonical {@link EventType}.
     *
     * @throws UnmappableEventException when this adapter has no mapping for it - the event is
     *         dead-lettered, never guessed at
     */
    protected EventType resolveEventType(RawEventEnvelope raw) {
        EventType type = eventTypes.get(normalizeKey(raw.sourceEventType()));
        if (type == null) {
            throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "no mapping in " + getClass().getSimpleName() + "; known types="
                            + eventTypes.keySet());
        }
        return type;
    }

    // --- envelope assembly ----------------------------------------------------------------------

    /**
     * Assembles the canonical envelope from the parts an adapter has derived.
     *
     * <p>Three properties are enforced here and must not be re-implemented per adapter:
     * <ol>
     *   <li>{@code eventId} is derived deterministically from the raw event id and the canonical
     *       type, so a replay of the raw topic produces byte-identical ids and every downstream
     *       consumer's {@code processed_events} claim suppresses the repeat;</li>
     *   <li>{@code occurredAt} is the source's own instant and {@code observedAt} is normalization
     *       time - a late event stays visibly late;</li>
     *   <li>{@code causationId} is the raw event id, so the chain back to the original webhook is
     *       never lost.</li>
     * </ol>
     */
    protected CanonicalEvent envelope(RawEventEnvelope raw,
                                      EventType eventType,
                                      String aggregateId,
                                      Instant occurredAt,
                                      Instant observedAt,
                                      JsonNode payload) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "no aggregate id could be derived for " + eventType);
        }
        Instant effectiveOccurredAt = occurredAt == null ? raw.receivedAt() : occurredAt;
        return CanonicalEvent.builder()
                .eventId(canonicalEventId(raw.rawEventId(), eventType))
                .eventType(eventType)
                .schemaVersion(CanonicalEvent.CURRENT_SCHEMA_VERSION)
                .aggregateType(eventType.aggregateType())
                .aggregateId(aggregateId)
                .merchantId(raw.merchantId())
                .correlationId(correlationId(raw, aggregateId))
                .causationId(raw.rawEventId())
                .occurredAt(effectiveOccurredAt)
                .observedAt(observedAt)
                .source(eventSource())
                .idempotencyKey(idempotencyKey(raw, eventType, aggregateId, effectiveOccurredAt))
                .payload(payload)
                .build();
    }

    /**
     * Deterministic canonical event id: a name-based UUID over
     * {@code namespace|rawEventId|eventType}.
     *
     * <p>This is the single most important idempotency property in the pipeline. Normalization is
     * a pure function of the raw record, so re-running it - after a crash, after a consumer group
     * reset, after a deliberate replay - yields the same {@code eventId}, and
     * {@code ProcessedEventRepository.markProcessed} in every downstream service turns the repeat
     * into a no-op instead of a double-applied state change.
     */
    public static String canonicalEventId(String rawEventId, EventType eventType) {
        String seed = EVENT_ID_NAMESPACE + "|" + rawEventId + "|" + eventType.name();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Correlation id for the canonical event, in preference order: an explicit correlation header
     * from ingestion, a correlation id in the body, the transaction the fact belongs to, and
     * finally the raw event id. Preferring the transaction means all events about one purchase
     * share a correlation id even when they arrive from four different systems.
     */
    protected String correlationId(RawEventEnvelope raw, String fallback) {
        String header = raw.header(EventHeaders.CORRELATION_ID);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String fromHeaders = raw.header("X-Correlation-Id");
        if (fromHeaders != null && !fromHeaders.isBlank()) {
            return fromHeaders;
        }
        String fromBody = Payloads.text(raw.body(), "correlationId", "correlation_id",
                "metadata.correlationId", "metadata.correlation_id");
        if (fromBody != null) {
            return fromBody;
        }
        String transactionId = transactionIdHint(raw);
        return transactionId != null ? transactionId : fallback;
    }

    /**
     * Best-effort transaction id from the source body, used for correlation. Adapters that know a
     * better location override this.
     */
    protected String transactionIdHint(RawEventEnvelope raw) {
        return Payloads.text(raw.body(), "transactionId", "transaction_id",
                "metadata.transactionId", "metadata.transaction_id");
    }

    /**
     * Stable idempotency key for the canonical event.
     *
     * <p>An explicit key from ingestion wins - that is the {@code Idempotency-Key} header the
     * source system sent, and it is the strongest statement available about "this is the same
     * fact". Otherwise a key is derived from the fact itself, so two deliveries of one webhook
     * under different raw ids still collapse downstream.
     */
    protected String idempotencyKey(RawEventEnvelope raw, EventType eventType, String aggregateId,
                                    Instant occurredAt) {
        String supplied = raw.idempotencyKey();
        if (supplied != null && !supplied.equals(raw.rawEventId())) {
            return supplied;
        }
        return sourceSystem + ":" + eventType.name() + ":" + aggregateId + ":" + occurredAt.toEpochMilli();
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Lower-cases and strips the separators source systems disagree about. */
    protected static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' || c == '_' || c == '/' || c == ' ' || c == '.' || c == ':') {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    /**
     * Ensures an identifier carries its contract prefix. Source systems send bare references
     * ({@code 9f2c}); PDEI ids are prefixed ({@code PAY-9f2c}) so an id is self-describing in a log
     * line, a URL and a partition key.
     */
    protected static String prefixed(String prefix, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith(prefix) ? trimmed : prefix + trimmed;
    }

    /** {@link #prefixed} over the first candidate path that carries a value. */
    protected static String prefixedFrom(JsonNode body, String prefix, String... paths) {
        return prefixed(prefix, Payloads.text(body, paths));
    }
}
