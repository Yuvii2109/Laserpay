package com.laserpay.pdei.common.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Kafka record header names (docs/SHARED-LIBRARY-API.md section 1.5).
 *
 * <p>These duplicate a few envelope fields on purpose: a consumer can dedupe on
 * {@link #EVENT_ID}, route on {@link #EVENT_TYPE} or dead-letter on a schema mismatch
 * <em>without</em> deserialising the body - which is exactly what is needed when the body is the
 * thing that failed to parse.
 *
 * <p>{@link #TRACEPARENT} carries W3C trace context across the async boundary so a trace in Tempo
 * spans ingestion, normalisation, state building and readiness as one story
 * (PLATFORM-CONTRACT section 13).
 *
 * <p>Header values are always UTF-8 encoded strings.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "pdei-event-id";
    public static final String EVENT_TYPE = "pdei-event-type";
    public static final String MERCHANT_ID = "pdei-merchant-id";
    public static final String CORRELATION_ID = "pdei-correlation-id";
    public static final String SCHEMA_VERSION = "pdei-schema-version";
    public static final String TRACEPARENT = "traceparent";
    /** Delivery attempt counter, incremented on each retry before dead-lettering. */
    public static final String ATTEMPT = "pdei-attempt";

    public static final List<String> ALL = List.of(
            EVENT_ID, EVENT_TYPE, MERCHANT_ID, CORRELATION_ID, SCHEMA_VERSION, TRACEPARENT, ATTEMPT);

    private EventHeaders() {
    }

    /** Encodes a header value; {@code null} becomes an empty byte array. */
    public static byte[] encode(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    /** Decodes a header value; {@code null} or empty becomes {@code null}. */
    public static String decode(byte[] value) {
        return value == null || value.length == 0 ? null : new String(value, StandardCharsets.UTF_8);
    }
}
