package com.laserpay.pdei.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;

import java.time.Instant;
import java.util.Objects;

/**
 * The canonical event envelope (PLATFORM-CONTRACT section 3). Everything downstream of
 * normalization-worker speaks this type and nothing else.
 *
 * <p>Field names are the wire contract and are mirrored by {@code pdei_ai.models.events.CanonicalEvent}
 * (Pydantic) and {@code frontend/src/lib/types/events.ts}. Adding, renaming or reordering a field
 * here is a three-language change.
 *
 * <p>Design notes:
 * <ul>
 *   <li><strong>{@code occurredAt} vs {@code observedAt}</strong> - the first is when the fact
 *       happened in the source system, the second is when PDEI saw it. Both are kept because the
 *       platform assumes late and out-of-order delivery (reference section 39.10); readiness and
 *       timelines order by {@code occurredAt}, lag metrics use the difference.</li>
 *   <li><strong>{@code idempotencyKey}</strong> - a stable, source-derived string. Redelivery of
 *       the same fact under a fresh {@code eventId} still collapses to one state change.</li>
 *   <li><strong>{@code payload}</strong> - kept as a {@link JsonNode} so the envelope stays schema
 *       agnostic; consumers project it with {@link #payloadAs(Class, ObjectMapper)}.</li>
 * </ul>
 *
 * <p>The compact constructor fills the tolerable defaults (schema version, observedAt,
 * correlationId, idempotencyKey, aggregateType, payload) and rejects anything that would make the
 * event unroutable or unauditable.
 */
public record CanonicalEvent(String eventId,
                             EventType eventType,
                             int schemaVersion,
                             AggregateType aggregateType,
                             String aggregateId,
                             String merchantId,
                             String correlationId,
                             String causationId,
                             Instant occurredAt,
                             Instant observedAt,
                             EventSource source,
                             String idempotencyKey,
                             JsonNode payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CanonicalEvent {
        eventId = requireText(eventId, "eventId");
        if (eventType == null) {
            throw new ValidationException("eventType is required on CanonicalEvent");
        }
        aggregateId = requireText(aggregateId, "aggregateId");
        merchantId = requireText(merchantId, "merchantId");
        if (occurredAt == null) {
            throw new ValidationException("occurredAt is required on CanonicalEvent");
        }
        if (source == null) {
            throw new ValidationException("source is required on CanonicalEvent");
        }

        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        aggregateType = aggregateType == null ? eventType.aggregateType() : aggregateType;
        observedAt = observedAt == null ? occurredAt : observedAt;
        correlationId = isBlank(correlationId) ? eventId : correlationId;
        causationId = isBlank(causationId) ? null : causationId;
        idempotencyKey = isBlank(idempotencyKey) ? eventId : idempotencyKey;
        payload = payload == null ? Json.mapper().createObjectNode() : payload;
    }

    /**
     * Kafka partition key, mandatory across every topic (PLATFORM-CONTRACT section 4):
     * {@code merchantId + ":" + aggregateId}. This keeps all events for one aggregate on one
     * partition, which is what makes single-threaded, ordered state building correct.
     */
    public String partitionKey() {
        return merchantId + ":" + aggregateId;
    }

    /** Binds the payload to a typed projection. */
    public <T> T payloadAs(Class<T> type, ObjectMapper mapper) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        try {
            return mapper.treeToValue(payload, type);
        } catch (Exception e) {
            throw new ValidationException(
                    "Event " + eventId + " payload is not a valid " + type.getSimpleName(), e);
        }
    }

    /** Same as {@link #payloadAs(Class, ObjectMapper)} using the shared {@link Json#mapper()}. */
    public <T> T payloadAs(Class<T> type) {
        return payloadAs(type, Json.mapper());
    }

    public boolean is(EventType type) {
        return eventType == type;
    }

    /** Milliseconds between the fact happening and PDEI observing it; never negative. */
    public long ingestionLagMillis() {
        long lag = observedAt.toEpochMilli() - occurredAt.toEpochMilli();
        return Math.max(0L, lag);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Pre-populated builder for deriving a follow-on event (copies every field). */
    public Builder toBuilder() {
        return new Builder()
                .eventId(eventId)
                .eventType(eventType)
                .schemaVersion(schemaVersion)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .merchantId(merchantId)
                .correlationId(correlationId)
                .causationId(causationId)
                .occurredAt(occurredAt)
                .observedAt(observedAt)
                .source(source)
                .idempotencyKey(idempotencyKey)
                .payload(payload);
    }

    private static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new ValidationException(field + " is required on CanonicalEvent");
        }
        return value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Fluent builder. Producers typically set {@code eventType}, ids, timestamps and payload and
     * let the compact constructor derive the rest.
     */
    public static final class Builder {

        private String eventId;
        private EventType eventType;
        private int schemaVersion = CURRENT_SCHEMA_VERSION;
        private AggregateType aggregateType;
        private String aggregateId;
        private String merchantId;
        private String correlationId;
        private String causationId;
        private Instant occurredAt;
        private Instant observedAt;
        private EventSource source;
        private String idempotencyKey;
        private JsonNode payload;

        private Builder() {
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder eventType(EventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder aggregateType(AggregateType aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder merchantId(String merchantId) {
            this.merchantId = merchantId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder causationId(String causationId) {
            this.causationId = causationId;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder observedAt(Instant observedAt) {
            this.observedAt = observedAt;
            return this;
        }

        public Builder source(EventSource source) {
            this.source = source;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder payload(JsonNode payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Converts any POJO/record/Map to the payload tree with the shared mapper. Deliberately a
         * distinct name rather than an overload of {@link #payload(JsonNode)}, so that a
         * {@code null} argument can never be ambiguous at the call site.
         */
        public Builder payloadFrom(Object payload) {
            this.payload = payload == null ? null : Json.tree(payload);
            return this;
        }

        /**
         * Marks this event as caused by {@code parent}: inherits {@code correlationId} and
         * {@code merchantId}, and records the parent's id as {@code causationId}. This is how the
         * causal chain from a raw PSP webhook to a submitted representment stays traceable.
         */
        public Builder causedBy(CanonicalEvent parent) {
            if (parent != null) {
                this.causationId = parent.eventId();
                this.correlationId = parent.correlationId();
                if (this.merchantId == null) {
                    this.merchantId = parent.merchantId();
                }
            }
            return this;
        }

        public CanonicalEvent build() {
            return new CanonicalEvent(eventId, eventType, schemaVersion, aggregateType, aggregateId,
                    merchantId, correlationId, causationId, occurredAt, observedAt, source,
                    idempotencyKey, payload);
        }
    }
}
