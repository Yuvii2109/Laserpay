package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.common.event.CanonicalEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The WebSocket / SSE frame envelope, exactly as PLATFORM-CONTRACT.md section 8.1 declares it:
 *
 * <pre>{@code
 * { "type": "READINESS_UPDATED", "at": "2026-08-26T10:15:30.123Z", "merchantId": "MER-0001", "data": {} }
 * }</pre>
 *
 * <p>Four fields, no more. The frontend's {@code useControlTowerSocket} hook is written against this
 * shape, and anything extra added here would have to be added to the TypeScript type as well.</p>
 *
 * @param merchantId the merchant this frame concerns; null only for a global HEARTBEAT
 * @param data       the frame body; always an object, never null, so a client can index into it
 *                   without a guard
 */
public record StreamFrame(
        FrameType type,
        Instant at,
        String merchantId,
        Map<String, Object> data) {

    public StreamFrame {
        // Not Map.copyOf: that rejects null values, and a frame legitimately carries a null field
        // when an event omits an optional identifier. An unmodifiable LinkedHashMap also keeps the
        // insertion order, so the JSON reads the same way every time.
        data = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static StreamFrame heartbeat(Instant at) {
        return new StreamFrame(FrameType.HEARTBEAT, at, null, Map.of());
    }

    public static StreamFrame heartbeat(Instant at, String merchantId, int subscribers) {
        return new StreamFrame(FrameType.HEARTBEAT, at, merchantId,
                Map.of("subscribers", subscribers));
    }

    /**
     * Project a canonical event into a frame.
     *
     * <p>The payload is not forwarded wholesale. A canonical event's payload can be large and can
     * carry fields the browser has no business seeing, and the control tower only needs enough to
     * decide which query to invalidate. The identifiers plus the original event type are exactly
     * that, and anything more detailed is one REST call away.</p>
     *
     * @return the frame, or null when the event type is not shown on the control tower
     */
    public static StreamFrame from(CanonicalEvent event) {
        FrameType type = FrameType.forEvent(event.eventType());
        if (type == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.eventId());
        data.put("eventType", event.eventType().name());
        data.put("aggregateType", event.aggregateType() == null ? null : event.aggregateType().name());
        data.put("aggregateId", event.aggregateId());
        data.put("correlationId", event.correlationId());
        data.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        data.put("source", event.source() == null ? null : event.source().name());
        copyIfPresent(event, data, "transactionId");
        copyIfPresent(event, data, "evidenceId");
        copyIfPresent(event, data, "disputeId");
        copyIfPresent(event, data, "caseId");
        copyIfPresent(event, data, "score");
        copyIfPresent(event, data, "band");
        copyIfPresent(event, data, "status");
        copyIfPresent(event, data, "severity");
        copyIfPresent(event, data, "gapType");
        return new StreamFrame(type, event.observedAt() == null ? event.occurredAt() : event.observedAt(),
                event.merchantId(), data);
    }

    private static void copyIfPresent(CanonicalEvent event, Map<String, Object> data, String field) {
        if (event.payload() == null || !event.payload().hasNonNull(field)) {
            return;
        }
        var node = event.payload().get(field);
        if (node.isNumber()) {
            data.put(field, node.numberValue());
        } else if (node.isBoolean()) {
            data.put(field, node.booleanValue());
        } else {
            data.put(field, node.asText());
        }
    }
}
