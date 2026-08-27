package com.laserpay.pdei.statebuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.json.Json;

import java.time.Instant;

/** Fixtures for building canonical events in tests. */
public final class Events {

    public static final String MERCHANT_ID = "MER-0001";
    public static final String TRANSACTION_ID = "TX-82918";
    public static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private Events() {
    }

    /** A canonical event with a generated-but-stable id derived from its own coordinates. */
    public static CanonicalEvent of(EventType type, String aggregateId, Instant occurredAt,
                                    String payloadJson) {
        return of(type.name() + ":" + aggregateId + ":" + occurredAt, type, aggregateId, occurredAt,
                payloadJson);
    }

    public static CanonicalEvent of(String eventId, EventType type, String aggregateId,
                                    Instant occurredAt, String payloadJson) {
        JsonNode payload = Json.readTree(payloadJson);
        return CanonicalEvent.builder()
                .eventId(eventId)
                .eventType(type)
                .schemaVersion(1)
                .aggregateType(type.aggregateType())
                .aggregateId(aggregateId)
                .merchantId(MERCHANT_ID)
                .correlationId(TRANSACTION_ID)
                .occurredAt(occurredAt)
                .observedAt(OBSERVED_AT)
                .source(EventSource.PSP_ADAPTER)
                .payload(payload)
                .build();
    }

    /** The same fact seen again from a different source system. */
    public static CanonicalEvent withSource(CanonicalEvent event, EventSource source) {
        return event.toBuilder().source(source).build();
    }

    /** Canonical money JSON: the only monetary shape on the wire. */
    public static String money(long amountMinor, String currency) {
        return "{\"amountMinor\": " + amountMinor + ", \"currency\": \"" + currency + "\"}";
    }
}
