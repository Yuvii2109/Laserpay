package com.laserpay.pdei.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laserpay.pdei.common.error.ValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The single JSON configuration for every Java service (docs/SHARED-LIBRARY-API.md section 1.7).
 *
 * <p>Configuration is fixed so that a payload written by one service always round-trips through
 * another: ISO-8601 instants (never epoch numbers, never {@code LocalDateTime}), {@code NON_NULL}
 * inclusion, and tolerance of unknown properties so a newer producer cannot break an older
 * consumer (reference: assume schema drift, tolerate late and unexpected data).
 *
 * <p>{@link #canonical(JsonNode)} is the hashing form: recursively key-sorted, whitespace-free.
 * It is what every SHA-256 in the platform is computed over, so it must never change casually -
 * changing it invalidates every stored audit chain hash.
 */
public final class Json {

    private static final ObjectMapper MAPPER = buildMapper();
    private static final ObjectWriter CANONICAL_WRITER = MAPPER.writer();

    private Json() {
    }

    /** Shared, fully configured mapper. Do not mutate; call {@link #newMapper()} if you must. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** An independent mapper with identical configuration, safe to reconfigure. */
    public static ObjectMapper newMapper() {
        return buildMapper();
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return m;
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Failed to serialise " + typeOf(o), e);
        }
    }

    public static <T> T read(String s, Class<T> t) {
        try {
            return MAPPER.readValue(s, t);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Failed to deserialise " + t.getSimpleName(), e);
        }
    }

    public static JsonNode readTree(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Failed to parse JSON", e);
        }
    }

    public static JsonNode tree(Object o) {
        return MAPPER.valueToTree(o);
    }

    public static <T> T fromTree(JsonNode node, Class<T> t) {
        try {
            return MAPPER.treeToValue(node, t);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Failed to bind JSON to " + t.getSimpleName(), e);
        }
    }

    /**
     * Deterministic serialisation used for hashing: object keys sorted lexicographically at every
     * depth, arrays left in order (array order is semantic), no insignificant whitespace.
     *
     * <p>{@code null} and missing nodes render as the four characters {@code null} so that a hash
     * can always be computed.
     */
    public static String canonical(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "null";
        }
        try {
            return CANONICAL_WRITER.writeValueAsString(sort(node));
        } catch (JsonProcessingException e) {
            throw new ValidationException("Failed to canonicalise JSON", e);
        }
    }

    /** Convenience: canonical form of an arbitrary object. */
    public static String canonical(Object o) {
        return canonical(tree(o));
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.getNodeFactory().objectNode();
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            Collections.sort(names);
            for (String name : names) {
                out.set(name, sort(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.getNodeFactory().arrayNode(node.size());
            for (JsonNode child : node) {
                out.add(sort(child));
            }
            return out;
        }
        return node;
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
