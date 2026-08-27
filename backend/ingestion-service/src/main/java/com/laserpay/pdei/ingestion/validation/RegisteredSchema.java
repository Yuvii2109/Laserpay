package com.laserpay.pdei.ingestion.validation;

import com.networknt.schema.JsonSchema;
import java.util.List;

/**
 * A compiled JSON Schema plus the PDEI metadata read out of it at load time.
 *
 * <p>The metadata comes from the {@code x-pdei-*} extension keywords that
 * {@code schemas/events/*.schema.json} carry ({@code x-pdei-event-type},
 * {@code x-pdei-aggregate-type}, {@code x-pdei-origin}). JSON Schema ignores unknown keywords, so
 * these are free annotations that keep the schema file self-describing rather than requiring a
 * separate manifest that would inevitably drift.
 *
 * @param key            normalised lookup key (see {@link SchemaRegistry#normalizeKey(String)})
 * @param name           file stem, e.g. {@code payment-created}
 * @param eventType      value of {@code x-pdei-event-type}, or null
 * @param aggregateType  value of {@code x-pdei-aggregate-type}, or null
 * @param origin         value of {@code x-pdei-origin}: {@code EXTERNAL} or {@code INTERNAL}
 * @param title          schema {@code title}
 * @param description    schema {@code description}
 * @param schemaId       schema {@code $id}
 * @param location       classpath URL or filesystem path this copy was loaded from
 * @param requiredFields top-level {@code required} list
 * @param schema         the compiled validator
 */
public record RegisteredSchema(String key,
                               String name,
                               String eventType,
                               String aggregateType,
                               String origin,
                               String title,
                               String description,
                               String schemaId,
                               String location,
                               List<String> requiredFields,
                               JsonSchema schema) {

    public static final String ORIGIN_EXTERNAL = "EXTERNAL";
    public static final String ORIGIN_INTERNAL = "INTERNAL";

    public RegisteredSchema {
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }

    /**
     * True when this schema describes a fact that external systems are expected to submit. Internal
     * event payloads (evidence, readiness, case, audit) are registered too - they are the referee
     * for the producing services and for the simulator - but ingestion does not expect an adapter to
     * push them through the front door.
     */
    public boolean isExternal() {
        return origin == null || ORIGIN_EXTERNAL.equals(origin);
    }
}
