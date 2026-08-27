package com.laserpay.pdei.ingestion.model;

import java.util.List;

/**
 * What {@code GET /ingest/v1/schemas} returns: one row per registered schema, without the compiled
 * schema itself.
 *
 * <p>This is how an adapter author discovers what ingestion will validate their payload against,
 * and how an operator confirms that a schema dropped into the mounted {@code /schemas/events}
 * directory was actually picked up ({@code location} shows which copy won).
 *
 * @param name           file stem, e.g. {@code payment-created}
 * @param key            normalised lookup key used to resolve a source event type
 * @param eventType      canonical {@code EventType} this payload belongs to, when declared
 * @param aggregateType  canonical {@code AggregateType}, when declared
 * @param origin         {@code EXTERNAL} (ingestible) or {@code INTERNAL} (platform-produced)
 * @param title          schema title
 * @param description    schema description
 * @param schemaId       the schema's {@code $id}
 * @param location       where this copy was loaded from (classpath URL or filesystem path)
 * @param requiredFields top-level required properties, the most common adapter mistake
 */
public record SchemaDescriptor(String name,
                               String key,
                               String eventType,
                               String aggregateType,
                               String origin,
                               String title,
                               String description,
                               String schemaId,
                               String location,
                               List<String> requiredFields) {

    public SchemaDescriptor {
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }
}
