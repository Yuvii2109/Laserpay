package com.laserpay.pdei.ingestion.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.ingestion.model.SchemaDescriptor;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads and indexes the JSON Schemas that ingestion validates against.
 *
 * <p><strong>Two sources, in order.</strong> First the classpath
 * ({@code classpath*:schemas/events/*.schema.json}, copied into the jar from the repository's
 * {@code /schemas/events} at build time), then every configured filesystem directory (default
 * {@code /schemas/events}, meant to be a mounted volume). Later wins, so an operator can correct a
 * schema in a running environment without rebuilding an image, and {@code GET /ingest/v1/schemas}
 * reports which copy is live via {@link RegisteredSchema#location()}.
 *
 * <p><strong>Self-contained schemas.</strong> Every PDEI schema resolves its {@code $ref}s inside
 * its own {@code $defs}. No cross-file references means no URI resolver, no network fetch and no
 * load-order dependency - a schema either compiles on its own or it is broken. This is the reason
 * {@code money} is repeated in each file rather than shared, and it is a deliberate trade of a
 * little duplication for a lot of operational simplicity.
 *
 * <p><strong>Key normalisation.</strong> Source systems spell the same fact a dozen ways
 * ({@code PaymentCaptured}, {@code payment-captured}, {@code payment_captured},
 * {@code payment.captured}). Lookup keys are folded to upper-case alphanumerics so all of those
 * resolve to one schema, with {@code ingestion.schemas.aliases} covering the genuinely different
 * vocabularies such as {@code payment_intent.succeeded}.
 */
@Component
public class SchemaRegistry {

    /** Schema file name of the submission/topic envelope (PLATFORM-CONTRACT section 4). */
    public static final String RAW_EVENT_SCHEMA = "raw-event";

    /** Schema file name of the canonical envelope (PLATFORM-CONTRACT section 3). */
    public static final String CANONICAL_EVENT_SCHEMA = "canonical-event";

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);
    private static final String SUFFIX = ".schema.json";

    private final IngestionProperties properties;
    private final ObjectMapper mapper;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** Normalised key -> schema. Rebuilt wholesale by {@link #reload()}; never mutated in place. */
    private final Map<String, RegisteredSchema> byKey = new ConcurrentHashMap<>();
    /** File stem -> schema, preserving load order for {@code GET /schemas}. */
    private final Map<String, RegisteredSchema> byName = new ConcurrentHashMap<>();

    public SchemaRegistry(IngestionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    public void reload() {
        Map<String, RegisteredSchema> loaded = new LinkedHashMap<>();
        loadFromClasspath(loaded);
        for (String directory : properties.getSchemas().getDirectories()) {
            loadFromDirectory(directory, loaded);
        }

        byName.clear();
        byKey.clear();
        byName.putAll(loaded);
        for (RegisteredSchema registered : loaded.values()) {
            index(registered);
        }
        applyAliases();

        if (loaded.isEmpty()) {
            log.error("No JSON Schemas loaded from classpath '{}' or directories {} - ingestion will "
                            + "accept anything structurally valid. Check the build's resource copy of /schemas/events.",
                    properties.getSchemas().getClasspathLocation(), properties.getSchemas().getDirectories());
        } else {
            log.info("Loaded {} JSON Schemas ({} lookup keys); envelope schema '{}' {}",
                    loaded.size(), byKey.size(), RAW_EVENT_SCHEMA,
                    loaded.containsKey(RAW_EVENT_SCHEMA) ? "present" : "MISSING");
        }
    }

    // --- lookup ---------------------------------------------------------------------------

    /**
     * Resolves the payload schema for a source event type.
     *
     * @param sourceEventType the source system's own vocabulary; may be null
     * @return the schema, or empty when nothing is registered for it
     */
    public Optional<RegisteredSchema> findByEventType(String sourceEventType) {
        if (sourceEventType == null || sourceEventType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(normalizeKey(sourceEventType)));
    }

    /** Looks a schema up by its exact file stem, e.g. {@code raw-event}. */
    public Optional<RegisteredSchema> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The submission/topic envelope schema, when it is registered. */
    public Optional<JsonSchema> envelopeSchema() {
        return findByName(RAW_EVENT_SCHEMA).map(RegisteredSchema::schema);
    }

    /**
     * The canonical envelope schema. Ingestion never produces canonical events, but it registers
     * and exposes the schema so that {@code GET /ingest/v1/schemas} is the one place to see the
     * whole registered vocabulary.
     */
    public Optional<JsonSchema> canonicalEventSchema() {
        return findByName(CANONICAL_EVENT_SCHEMA).map(RegisteredSchema::schema);
    }

    /** Every registered schema, sorted by name, without the compiled validators. */
    public List<SchemaDescriptor> descriptors() {
        return byName.values().stream()
                .sorted(Comparator.comparing(RegisteredSchema::name))
                .map(r -> new SchemaDescriptor(r.name(), r.key(), r.eventType(), r.aggregateType(),
                        r.origin(), r.title(), r.description(), r.schemaId(), r.location(),
                        r.requiredFields()))
                .toList();
    }

    public int size() {
        return byName.size();
    }

    /**
     * Folds a source event type or file stem into a lookup key: upper case, alphanumerics only.
     * {@code payment_intent.succeeded} and {@code PaymentIntentSucceeded} collapse to the same key.
     */
    public static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    // --- loading --------------------------------------------------------------------------

    private void loadFromClasspath(Map<String, RegisteredSchema> sink) {
        String pattern = properties.getSchemas().getClasspathLocation();
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver(getClass().getClassLoader())
                    .getResources(pattern);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(SUFFIX)) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    register(sink, stemOf(filename), mapper.readTree(in), describe(resource));
                } catch (IOException | RuntimeException e) {
                    log.error("Skipping unreadable classpath schema {}: {}", filename, e.toString());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan classpath schemas at '{}': {}", pattern, e.toString());
        }
    }

    private void loadFromDirectory(String directory, Map<String, RegisteredSchema> sink) {
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path dir = Paths.get(directory);
        if (!Files.isDirectory(dir)) {
            log.debug("Schema directory {} is absent; skipping", dir);
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> schemaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
            for (Path file : schemaFiles) {
                try (InputStream in = Files.newInputStream(file)) {
                    register(sink, stemOf(file.getFileName().toString()), mapper.readTree(in),
                            file.toAbsolutePath().toString());
                } catch (IOException | RuntimeException e) {
                    log.error("Skipping unreadable schema file {}: {}", file, e.toString());
                }
            }
            log.info("Loaded {} schema file(s) from directory {}", schemaFiles.size(), dir);
        } catch (IOException e) {
            log.error("Failed to list schema directory {}: {}", dir, e.toString());
        }
    }

    private void register(Map<String, RegisteredSchema> sink, String name, JsonNode node, String location) {
        JsonSchema compiled = factory.getSchema(node);
        RegisteredSchema registered = new RegisteredSchema(
                normalizeKey(name),
                name,
                text(node, "x-pdei-event-type"),
                text(node, "x-pdei-aggregate-type"),
                text(node, "x-pdei-origin"),
                text(node, "title"),
                text(node, "description"),
                text(node, "$id"),
                location,
                requiredOf(node),
                compiled);
        RegisteredSchema previous = sink.put(name, registered);
        if (previous != null) {
            log.info("Schema '{}' from {} overrides the copy from {}", name, location, previous.location());
        }
    }

    private void index(RegisteredSchema registered) {
        // File stem: payment-created -> PAYMENTCREATED
        byKey.put(registered.key(), registered);
        // Declared canonical event type: PaymentCreated -> PAYMENTCREATED (same key, cheap and explicit)
        if (registered.eventType() != null && !registered.eventType().isBlank()) {
            byKey.put(normalizeKey(registered.eventType()), registered);
        }
        // The $id filename, so a source may quote the schema id directly.
        if (registered.schemaId() != null && registered.schemaId().endsWith(SUFFIX)) {
            int slash = registered.schemaId().lastIndexOf('/');
            String idName = slash >= 0 ? registered.schemaId().substring(slash + 1) : registered.schemaId();
            byKey.put(normalizeKey(stemOf(idName)), registered);
        }
    }

    private void applyAliases() {
        for (Map.Entry<String, String> alias : properties.getSchemas().getAliases().entrySet()) {
            String from = normalizeKey(alias.getKey());
            RegisteredSchema target = byKey.get(normalizeKey(alias.getValue()));
            if (target == null) {
                log.error("Schema alias '{}' -> '{}' ignored: no schema registered under the target",
                        alias.getKey(), alias.getValue());
                continue;
            }
            byKey.put(from, target);
            log.info("Registered schema alias '{}' -> '{}'", alias.getKey(), target.name());
        }
    }

    /**
     * Compiles an ad-hoc schema from a tree. Used by tests and by any future dynamic registration
     * path; deliberately not exposed over HTTP - schemas are configuration, not user input.
     */
    public JsonSchema compile(JsonNode schemaNode) {
        if (schemaNode == null || !schemaNode.isObject()) {
            throw new ValidationException("A JSON Schema must be a JSON object");
        }
        return factory.getSchema(schemaNode);
    }

    private static String stemOf(String filename) {
        return filename.endsWith(SUFFIX)
                ? filename.substring(0, filename.length() - SUFFIX.length())
                : filename;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> requiredOf(JsonNode node) {
        JsonNode required = node.get("required");
        if (required == null || !required.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(required.size());
        required.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String describe(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription().toLowerCase(Locale.ROOT);
        }
    }
}
