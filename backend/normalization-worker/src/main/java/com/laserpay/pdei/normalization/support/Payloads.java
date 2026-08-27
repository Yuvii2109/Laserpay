package com.laserpay.pdei.normalization.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Reading helpers for untrusted, source-shaped JSON.
 *
 * <p>Every source system spells the same fact differently ({@code payment_id} / {@code paymentId} /
 * {@code id}), so adapters never index into a single fixed path: they list candidate paths in
 * preference order and take the first one that is present. That is what keeps schema drift in one
 * upstream system from becoming a code change across the whole worker.
 *
 * <p><strong>Money rule.</strong> {@link #money} never produces a {@code double}, {@code float} or
 * {@link java.math.BigDecimal}. Integral inputs are read as minor units directly; a decimal
 * <em>string</em> ("1299.00") is converted by integer digit shifting in
 * {@link #minorFromDecimalText}. A JSON floating-point literal for a monetary amount is rejected
 * outright rather than silently rounded - that is a producer bug and must surface as a dead letter.
 */
public final class Payloads {

    /**
     * Epoch values above this are milliseconds, below are seconds. 1e11 seconds is the year 5138
     * and 1e11 milliseconds is 1973, so the split is unambiguous for anything this platform sees.
     */
    private static final long EPOCH_MILLIS_THRESHOLD = 100_000_000_000L;

    private Payloads() {
    }

    // --- navigation -----------------------------------------------------------------------------

    /**
     * Resolves a dotted path ({@code data.object.amount}) against a node. Numeric segments index
     * into arrays ({@code lines.0.sku}).
     */
    public static JsonNode at(JsonNode node, String path) {
        if (node == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isNull()) {
                return null;
            }
            if (current.isArray() && isDigits(segment)) {
                current = current.get(Integer.parseInt(segment));
            } else {
                current = current.get(segment);
            }
        }
        return current == null || current.isNull() ? null : current;
    }

    /** First candidate path that resolves to a present, non-null node. */
    public static JsonNode first(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode found = at(node, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --- scalars --------------------------------------------------------------------------------

    /** First non-blank textual value among the candidate paths, or {@code null}. */
    public static String text(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode found = at(node, path);
            if (found == null || !found.isValueNode()) {
                continue;
            }
            String value = found.asText();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** {@link #text} with a fallback used when no candidate path is present. */
    public static String textOr(JsonNode node, String fallback, String... paths) {
        String value = text(node, paths);
        return value == null ? fallback : value;
    }

    public static Integer integer(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode found = at(node, path);
            if (found == null) {
                continue;
            }
            if (found.isIntegralNumber()) {
                return found.intValue();
            }
            if (found.isTextual()) {
                try {
                    return Integer.valueOf(found.asText().trim());
                } catch (NumberFormatException ignored) {
                    // not numeric text: fall through to the next candidate path
                }
            }
        }
        return null;
    }

    public static boolean bool(JsonNode node, boolean fallback, String... paths) {
        for (String path : paths) {
            JsonNode found = at(node, path);
            if (found == null) {
                continue;
            }
            if (found.isBoolean()) {
                return found.booleanValue();
            }
            if (found.isTextual()) {
                String value = found.asText().trim().toLowerCase(Locale.ROOT);
                if ("true".equals(value) || "yes".equals(value) || "1".equals(value)) {
                    return true;
                }
                if ("false".equals(value) || "no".equals(value) || "0".equals(value)) {
                    return false;
                }
            }
        }
        return fallback;
    }

    // --- time -----------------------------------------------------------------------------------

    /**
     * First parseable timestamp among the candidate paths. Accepts ISO-8601 text, epoch seconds and
     * epoch milliseconds (numeric or numeric-as-text).
     */
    public static Instant instant(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode found = at(node, path);
            if (found == null) {
                continue;
            }
            Instant parsed = toInstant(found);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * {@link #instant} with an explicit fallback. Adapters use this to preserve the source's
     * {@code occurredAt} and fall back to the ingestion receipt time only when the source omitted
     * every timestamp it could have sent.
     */
    public static Instant instantOr(JsonNode node, Instant fallback, String... paths) {
        Instant value = instant(node, paths);
        return value == null ? fallback : value;
    }

    private static Instant toInstant(JsonNode node) {
        if (node.isIntegralNumber()) {
            return fromEpoch(node.longValue());
        }
        if (!node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // not ISO-8601; a numeric string is the other shape seen in the wild
        }
        try {
            return fromEpoch(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant fromEpoch(long raw) {
        return Math.abs(raw) > EPOCH_MILLIS_THRESHOLD ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
    }

    // --- money ----------------------------------------------------------------------------------

    /**
     * Reads a monetary value from the first candidate path that carries one.
     *
     * <p>Accepted shapes, in order of preference:
     * <ul>
     *   <li>{@code {"amountMinor": 1299900, "currency": "INR"}} - the canonical shape;</li>
     *   <li>{@code {"amount_minor": 1299900, "currency": "inr"}} - snake_case variants;</li>
     *   <li>{@code {"amount": 1299900, "currency": "INR"}} with an <em>integral</em> amount, the
     *       PSP convention of quoting minor units;</li>
     *   <li>{@code {"amount": "1299.00", "currency": "INR"}} - a decimal <em>string</em>, converted
     *       by integer digit shifting;</li>
     *   <li>a bare integral scalar at the path, paired with {@code defaultCurrency}.</li>
     * </ul>
     *
     * @throws MonetaryPrecisionException when the amount is a JSON floating-point literal, which
     *         cannot be converted without a rounding decision this platform refuses to make
     */
    public static Money money(JsonNode node, String defaultCurrency, String... paths) {
        for (String path : paths) {
            JsonNode found = at(node, path);
            if (found == null) {
                continue;
            }
            Money money = toMoney(found, defaultCurrency);
            if (money != null) {
                return money;
            }
        }
        return null;
    }

    /** {@link #money} with a fallback, for optional monetary fields such as tax or shipping. */
    public static Money moneyOr(JsonNode node, Money fallback, String defaultCurrency, String... paths) {
        Money value = money(node, defaultCurrency, paths);
        return value == null ? fallback : value;
    }

    private static Money toMoney(JsonNode node, String defaultCurrency) {
        if (node.isObject()) {
            String currency = normalizeCurrency(
                    text(node, "currency", "currencyCode", "currency_code", "isoCurrency"),
                    defaultCurrency);
            if (currency == null) {
                return null;
            }
            JsonNode minor = first(node, "amountMinor", "amount_minor", "minorUnits", "minor_units",
                    "valueMinor", "value_minor");
            if (minor != null) {
                Long value = integralOf(minor, currency);
                if (value != null) {
                    return Money.of(value, currency);
                }
            }
            JsonNode amount = first(node, "amount", "value", "total");
            if (amount != null) {
                Long value = integralOf(amount, currency);
                if (value != null) {
                    return Money.of(value, currency);
                }
            }
            return null;
        }
        String currency = normalizeCurrency(null, defaultCurrency);
        if (currency == null) {
            return null;
        }
        Long value = integralOf(node, currency);
        return value == null ? null : Money.of(value, currency);
    }

    private static Long integralOf(JsonNode node, String currency) {
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            throw new MonetaryPrecisionException(
                    "monetary amount arrived as a JSON floating-point literal (" + node.asText()
                            + "); producers must send minor units or a decimal string");
        }
        if (node.isTextual()) {
            return minorFromDecimalText(node.asText(), currency);
        }
        return null;
    }

    /**
     * Converts a decimal <em>string</em> to minor units using integer arithmetic only.
     *
     * <p>"1299.00" with INR (2 fraction digits) becomes {@code 129900}. Excess fraction digits are
     * rejected rather than rounded: silently dropping a digit from a monetary value is exactly the
     * class of bug the money rule exists to prevent.
     *
     * @throws MonetaryPrecisionException when the text is not a plain decimal number, or carries
     *         more fraction digits than the currency allows
     */
    public static long minorFromDecimalText(String text, String currency) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new MonetaryPrecisionException("empty monetary amount");
        }
        boolean negative = trimmed.startsWith("-");
        if (negative || trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1);
        }
        int dot = trimmed.indexOf('.');
        String whole = dot < 0 ? trimmed : trimmed.substring(0, dot);
        String fraction = dot < 0 ? "" : trimmed.substring(dot + 1);
        if (whole.isEmpty()) {
            whole = "0";
        }
        if (!isDigits(whole) || (!fraction.isEmpty() && !isDigits(fraction))) {
            throw new MonetaryPrecisionException("not a decimal monetary amount: " + text);
        }
        int digits = Money.fractionDigits(currency);
        if (fraction.length() > digits) {
            throw new MonetaryPrecisionException("monetary amount " + text
                    + " has more fraction digits than " + currency + " allows (" + digits + ")");
        }
        StringBuilder padded = new StringBuilder(fraction);
        while (padded.length() < digits) {
            padded.append('0');
        }
        long minor;
        try {
            minor = Long.parseLong(whole + padded);
        } catch (NumberFormatException e) {
            throw new MonetaryPrecisionException("monetary amount out of range: " + text, e);
        }
        return negative ? -minor : minor;
    }

    /** Uppercases an ISO-4217 code and falls back when the source omitted it. */
    public static String normalizeCurrency(String currency, String fallback) {
        String candidate = currency == null || currency.isBlank() ? fallback : currency;
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return candidate.trim().toUpperCase(Locale.ROOT);
    }

    // --- writing --------------------------------------------------------------------------------

    /** A fresh canonical payload object built with the shared mapper's node factory. */
    public static ObjectNode object() {
        return Json.mapper().createObjectNode();
    }

    /** A fresh array node, for line items and similar repeated structures. */
    public static ArrayNode array() {
        return Json.mapper().createArrayNode();
    }

    /** Writes {@code {"amountMinor": ..., "currency": "..."}} - the only monetary shape on the wire. */
    public static void putMoney(ObjectNode target, String field, Money money) {
        if (money == null) {
            return;
        }
        ObjectNode node = target.putObject(field);
        node.put("amountMinor", money.amountMinor());
        node.put("currency", money.currency());
    }

    /** Writes an ISO-8601 UTC timestamp, or nothing when the value is absent. */
    public static void putInstant(ObjectNode target, String field, Instant value) {
        if (value != null) {
            target.put(field, value.toString());
        }
    }

    /** Writes a string, or nothing when it is null or blank - payloads stay free of empty noise. */
    public static void putText(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    /** Copies a sub-tree (address, geo, line array) verbatim when present. */
    public static void putNode(ObjectNode target, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
