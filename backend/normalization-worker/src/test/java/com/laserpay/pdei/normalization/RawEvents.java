package com.laserpay.pdei.normalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.json.Json;

import java.time.Instant;
import java.util.Map;

/** Test fixtures for building {@link RawEventEnvelope}s from literal source-shaped JSON. */
public final class RawEvents {

    public static final String MERCHANT_ID = "MER-0001";
    public static final Instant RECEIVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private RawEvents() {
    }

    public static RawEventEnvelope of(String sourceSystem, String sourceEventType, String bodyJson) {
        return of("raw-" + Math.abs(bodyJson.hashCode()), sourceSystem, sourceEventType, bodyJson,
                Map.of());
    }

    public static RawEventEnvelope of(String rawEventId, String sourceSystem, String sourceEventType,
                                      String bodyJson, Map<String, String> headers) {
        JsonNode body = Json.readTree(bodyJson);
        return new RawEventEnvelope(rawEventId, sourceSystem, sourceEventType, MERCHANT_ID,
                RECEIVED_AT, rawEventId, headers, body);
    }
}
