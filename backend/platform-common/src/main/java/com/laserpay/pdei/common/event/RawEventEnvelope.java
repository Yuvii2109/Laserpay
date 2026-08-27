package com.laserpay.pdei.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Untranslated source event as accepted by ingestion-service or emitted by simulator-service, and
 * published to {@code pdei.raw.events.v1} (PLATFORM-CONTRACT section 4).
 *
 * <p>The body is kept exactly as the source system sent it. normalization-worker is the only
 * component allowed to interpret it, and it is the component that turns this into a
 * {@link CanonicalEvent}. Keeping the raw form on its own topic means a normalisation bug can be
 * fixed and the whole stream replayed without asking the source systems for anything.
 *
 * <p>{@code sourceEventType} is the source system vocabulary (e.g. {@code payment_intent.succeeded}),
 * NOT a {@link EventType} - the mapping between the two lives in normalization-worker.
 */
public record RawEventEnvelope(String rawEventId,
                               String sourceSystem,
                               String sourceEventType,
                               String merchantId,
                               Instant receivedAt,
                               String idempotencyKey,
                               Map<String, String> headers,
                               JsonNode body) {

    public RawEventEnvelope {
        if (rawEventId == null || rawEventId.isBlank()) {
            throw new ValidationException("rawEventId is required on RawEventEnvelope");
        }
        if (sourceSystem == null || sourceSystem.isBlank()) {
            throw new ValidationException("sourceSystem is required on RawEventEnvelope");
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new ValidationException("merchantId is required on RawEventEnvelope");
        }
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        idempotencyKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? rawEventId
                : idempotencyKey;
        headers = headers == null || headers.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? Json.mapper().createObjectNode() : body;
    }

    /**
     * Partition key for {@code pdei.raw.events.v1}. A raw event has no aggregate id yet, so it is
     * keyed by merchant and idempotency key: same fact, same partition, so duplicates and their
     * originals are handled by the same consumer instance.
     */
    public String partitionKey() {
        return merchantId + ":" + idempotencyKey;
    }

    public String header(String name) {
        return headers.get(name);
    }
}
