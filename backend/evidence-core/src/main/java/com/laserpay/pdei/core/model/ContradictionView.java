package com.laserpay.pdei.core.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.laserpay.pdei.common.domain.GapSeverity;

import java.io.IOException;
import java.time.Instant;

/**
 * A conflict between two facts/artifacts describing the same transaction.
 *
 * <p>{@code left}, {@code right}, {@code field} and {@code detail} are the fields serialised into
 * {@link InvestigationContext#contradictions()} (platform contract 9.1). {@code left} / {@code right}
 * carry the evidence id when the conflicting fact is backed by evidence, otherwise the domain entity
 * id (SHP-, DLV-, ORD-, ...).</p>
 */
@JsonDeserialize(using = ContradictionView.Deserializer.class)
public record ContradictionView(
        String left,
        String right,
        String field,
        String detail,
        GapSeverity severity,
        String leftValue,
        String rightValue,
        Instant detectedAt) {

    public static ContradictionView of(String left, String right, String field, String detail,
                                       GapSeverity severity, Object leftValue, Object rightValue,
                                       Instant detectedAt) {
        return new ContradictionView(left, right, field, detail, severity,
                String.valueOf(leftValue), String.valueOf(rightValue), detectedAt);
    }

    /** Free-text contradiction, used when only a description is available (e.g. AI output). */
    public static ContradictionView narrative(String detail) {
        return new ContradictionView(null, null, null, detail, GapSeverity.MEDIUM, null, null, null);
    }

    /**
     * Tolerant deserializer: accepts both the object form and a plain string, so a schema drift in the
     * AI service never fails the whole investigation parse (the safety gate still runs afterwards).
     */
    public static final class Deserializer extends StdDeserializer<ContradictionView> {
        public Deserializer() {
            super(ContradictionView.class);
        }

        @Override
        public ContradictionView deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isTextual()) {
                return narrative(node.asText());
            }
            GapSeverity severity = GapSeverity.MEDIUM;
            if (node.hasNonNull("severity")) {
                try {
                    severity = GapSeverity.valueOf(node.get("severity").asText());
                } catch (IllegalArgumentException ignored) {
                    severity = GapSeverity.MEDIUM;
                }
            }
            Instant detectedAt = null;
            if (node.hasNonNull("detectedAt")) {
                try {
                    detectedAt = Instant.parse(node.get("detectedAt").asText());
                } catch (RuntimeException ignored) {
                    detectedAt = null;
                }
            }
            return new ContradictionView(
                    text(node, "left"), text(node, "right"), text(node, "field"), text(node, "detail"),
                    severity, text(node, "leftValue"), text(node, "rightValue"), detectedAt);
        }

        private static String text(JsonNode node, String field) {
            return node.hasNonNull(field) ? node.get(field).asText() : null;
        }
    }
}
