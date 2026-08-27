package com.laserpay.pdei.normalization.upcast;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.kafka.EventHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes the raw-event schema version.
 *
 * <p>{@link RawEventEnvelope} has no schema-version field of its own, so the version travels in the
 * {@code pdei-schema-version} Kafka header (mirrored into
 * {@link RawEventEnvelope#headers()} by ingestion) with the body's {@code schemaVersion} as a
 * fallback. Reading a header rather than the body is deliberate: a consumer must be able to decide
 * how to treat a record without deserialising a body that may itself be the thing that changed.
 *
 * <p>A missing version reads as {@link #UNVERSIONED} (0), not as "current". Legacy producers are
 * exactly the ones that do not set it, and each upcaster additionally checks for its own shape
 * marker, so a modern payload without the header still passes through untouched.
 */
public final class SchemaVersions {

    /** No version declared: treat as the oldest shape and let the chain decide. */
    public static final int UNVERSIONED = 0;

    private SchemaVersions() {
    }

    /** Declared schema version of a raw envelope, or {@link #UNVERSIONED}. */
    public static int read(RawEventEnvelope raw) {
        if (raw == null) {
            return UNVERSIONED;
        }
        String header = raw.header(EventHeaders.SCHEMA_VERSION);
        Integer parsed = parse(header);
        if (parsed != null) {
            return parsed;
        }
        JsonNode body = raw.body();
        if (body != null) {
            JsonNode node = body.get("schemaVersion");
            if (node == null) {
                node = body.get("schema_version");
            }
            if (node != null && node.isIntegralNumber()) {
                return node.intValue();
            }
            if (node != null && node.isTextual()) {
                Integer fromText = parse(node.asText());
                if (fromText != null) {
                    return fromText;
                }
            }
        }
        return UNVERSIONED;
    }

    /** Returns a copy of {@code raw} whose declared schema version is {@code version}. */
    public static RawEventEnvelope withVersion(RawEventEnvelope raw, int version) {
        Map<String, String> headers = new LinkedHashMap<>(raw.headers());
        headers.put(EventHeaders.SCHEMA_VERSION, Integer.toString(version));
        return new RawEventEnvelope(raw.rawEventId(), raw.sourceSystem(), raw.sourceEventType(),
                raw.merchantId(), raw.receivedAt(), raw.idempotencyKey(), headers, raw.body());
    }

    /** Returns a copy of {@code raw} with a replaced body, bumped to {@code version}. */
    public static RawEventEnvelope withBody(RawEventEnvelope raw, JsonNode body, int version) {
        Map<String, String> headers = new LinkedHashMap<>(raw.headers());
        headers.put(EventHeaders.SCHEMA_VERSION, Integer.toString(version));
        return new RawEventEnvelope(raw.rawEventId(), raw.sourceSystem(), raw.sourceEventType(),
                raw.merchantId(), raw.receivedAt(), raw.idempotencyKey(), headers, body);
    }

    /** Returns a copy of {@code raw} with a rewritten source event type, bumped to {@code version}. */
    public static RawEventEnvelope withSourceEventType(RawEventEnvelope raw, String sourceEventType,
                                                       int version) {
        Map<String, String> headers = new LinkedHashMap<>(raw.headers());
        headers.put(EventHeaders.SCHEMA_VERSION, Integer.toString(version));
        return new RawEventEnvelope(raw.rawEventId(), raw.sourceSystem(), sourceEventType,
                raw.merchantId(), raw.receivedAt(), raw.idempotencyKey(), headers, raw.body());
    }

    private static Integer parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
