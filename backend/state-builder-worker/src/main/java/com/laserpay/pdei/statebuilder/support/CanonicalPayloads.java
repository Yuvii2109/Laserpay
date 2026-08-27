package com.laserpay.pdei.statebuilder.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readers for <em>canonical</em> event payloads.
 *
 * <p>Deliberately much stricter than normalization-worker's equivalent. By the time an event
 * reaches this worker it has already been through a {@code SourceAdapter}, so the payload is known
 * to be in the shape documented in {@code docs/event-catalog.md}: camelCase field names, ISO-8601
 * instants, and money as {@code {"amountMinor": long, "currency": "XXX"}}. There is no vendor
 * variance left to tolerate, and tolerating it here would hide a normalization bug behind a
 * silently-defaulted field.
 *
 * <p>Money is read as {@code (long, String)} and nothing else. A payload that carries a
 * floating-point amount would already have been dead-lettered upstream; if one somehow arrives, the
 * read returns {@code null} rather than rounding.
 */
public final class CanonicalPayloads {

    private CanonicalPayloads() {
    }

    /** Trimmed text at {@code field}, or {@code null} when absent or blank. */
    public static String text(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** First non-null text among several candidate fields. */
    public static String text(JsonNode payload, String first, String... more) {
        String value = text(payload, first);
        if (value != null) {
            return value;
        }
        for (String field : more) {
            value = text(payload, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static Integer integer(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        return node != null && node.isIntegralNumber() ? node.intValue() : null;
    }

    public static boolean bool(JsonNode payload, String field, boolean fallback) {
        if (payload == null) {
            return fallback;
        }
        JsonNode node = payload.get(field);
        return node != null && node.isBoolean() ? node.booleanValue() : fallback;
    }

    /** ISO-8601 instant at {@code field}, or {@code null}. Never a {@code LocalDateTime}. */
    public static Instant instant(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** First parseable instant among several candidate fields. */
    public static Instant instant(JsonNode payload, String first, String... more) {
        Instant value = instant(payload, first);
        if (value != null) {
            return value;
        }
        for (String field : more) {
            value = instant(payload, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Money at {@code field} in the canonical {@code {amountMinor, currency}} shape.
     *
     * @return the value, or {@code null} when the field is absent or not in that shape
     */
    public static Money money(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode amount = node.get("amountMinor");
        JsonNode currency = node.get("currency");
        if (amount == null || !amount.isIntegralNumber() || currency == null || !currency.isTextual()) {
            return null;
        }
        return Money.of(amount.longValue(), currency.asText().trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** First present money value among several candidate fields. */
    public static Money money(JsonNode payload, String first, String... more) {
        Money value = money(payload, first);
        if (value != null) {
            return value;
        }
        for (String field : more) {
            value = money(payload, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Currency of the first money value present, or {@code fallback}. */
    public static String currencyOr(String fallback, Money... candidates) {
        for (Money candidate : candidates) {
            if (candidate != null) {
                return candidate.currency();
            }
        }
        return fallback;
    }

    /**
     * A nested object at {@code field} converted to a JSONB-friendly map, or {@code null}.
     * Used for addresses and other free-form sub-documents that the schema stores as {@code jsonb}.
     */
    public static Map<String, Object> objectMap(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        if (node == null || !node.isObject()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = Json.mapper().convertValue(node, LinkedHashMap.class);
        return map;
    }
}
