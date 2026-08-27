package com.laserpay.pdei.normalization.upcast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.normalization.support.Payloads;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v0 to v1: rewrites the pre-contract {@code *_cents} / {@code *_paise} monetary convention into the
 * canonical {@code {amountMinor, currency}} object.
 *
 * <p>The earliest producers wrote {@code {"amount_cents": 129900, "currency": "inr"}} - the amount
 * and its currency as sibling scalars, with the unit encoded in the field <em>name</em>. That works
 * until a second currency appears with different fraction digits, at which point the field name is
 * a lie. This upcaster pairs each such field with the nearest currency in scope and emits the
 * explicit object shape, recursing through nested objects and arrays so order lines are migrated
 * too.
 *
 * <p>The original scalar is left in place. Nothing is discarded during an upcast: a later fix must
 * still be able to see what the producer actually sent.
 */
public class LegacyMinorUnitsUpcaster implements EventUpcaster {

    /** Suffixes that name a minor-unit amount in the legacy convention. */
    private static final List<String> MINOR_SUFFIXES = List.of("_cents", "_paise", "Cents", "Paise");

    private static final Set<String> CURRENCY_FIELDS =
            Set.of("currency", "currency_code", "currencyCode", "presentment_currency");

    private final String defaultCurrency;

    public LegacyMinorUnitsUpcaster(String defaultCurrency) {
        this.defaultCurrency = Payloads.normalizeCurrency(defaultCurrency, "INR");
    }

    @Override
    public int fromVersion() {
        return SchemaVersions.UNVERSIONED;
    }

    @Override
    public boolean supports(RawEventEnvelope raw) {
        return raw != null
                && SchemaVersions.read(raw) <= SchemaVersions.UNVERSIONED
                && containsLegacyField(raw.body());
    }

    @Override
    public RawEventEnvelope upcast(RawEventEnvelope raw) {
        JsonNode migrated = raw.body().deepCopy();
        rewrite(migrated, defaultCurrency);
        return SchemaVersions.withBody(raw, migrated, SchemaVersions.UNVERSIONED + 1);
    }

    // --- traversal ------------------------------------------------------------------------------

    private static boolean containsLegacyField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                if (legacyBaseName(names.next()) != null) {
                    return true;
                }
            }
            for (JsonNode child : node) {
                if (containsLegacyField(child)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsLegacyField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rewrite(JsonNode node, String inheritedCurrency) {
        if (node instanceof ObjectNode object) {
            String currency = currencyIn(object, inheritedCurrency);
            for (Map.Entry<String, JsonNode> entry : fieldsOf(object)) {
                String base = legacyBaseName(entry.getKey());
                if (base != null && entry.getValue().isIntegralNumber()) {
                    ObjectNode money = object.putObject(base);
                    money.put("amountMinor", entry.getValue().longValue());
                    money.put("currency", currency);
                }
            }
            for (Map.Entry<String, JsonNode> entry : fieldsOf(object)) {
                rewrite(entry.getValue(), currency);
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                rewrite(child, inheritedCurrency);
            }
        }
    }

    /** Snapshot of the fields, so the loop can add siblings without a concurrent modification. */
    private static List<Map.Entry<String, JsonNode>> fieldsOf(ObjectNode object) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = object.fields();
        while (it.hasNext()) {
            entries.add(it.next());
        }
        return entries;
    }

    private String currencyIn(ObjectNode object, String inherited) {
        for (String field : CURRENCY_FIELDS) {
            JsonNode node = object.get(field);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText().trim().toUpperCase(Locale.ROOT);
            }
        }
        return inherited;
    }

    /**
     * The canonical field name for a legacy minor-unit field, or {@code null} when the name is not
     * in the legacy convention. {@code amount_cents} yields {@code amount};
     * {@code declaredValueCents} yields {@code declaredValue}.
     */
    private static String legacyBaseName(String fieldName) {
        for (String suffix : MINOR_SUFFIXES) {
            if (fieldName.length() > suffix.length() && fieldName.endsWith(suffix)) {
                return fieldName.substring(0, fieldName.length() - suffix.length());
            }
        }
        return null;
    }
}
