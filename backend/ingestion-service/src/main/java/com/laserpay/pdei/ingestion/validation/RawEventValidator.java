package com.laserpay.pdei.ingestion.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.ingestion.model.FieldError;
import com.laserpay.pdei.ingestion.model.IngestRequest;
import com.laserpay.pdei.ingestion.model.RejectedEvent;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates a submitted raw event and reports <em>where</em> it is wrong.
 *
 * <p>Three passes, cheapest first:
 *
 * <ol>
 *   <li><strong>Structural</strong> - the four things ingestion cannot proceed without
 *       ({@code sourceSystem}, {@code sourceEventType}, {@code merchantId}, a JSON object body).
 *       Checked in Java rather than only by schema so that a missing or broken
 *       {@code raw-event.schema.json} cannot silently disable the front door.</li>
 *   <li><strong>Envelope</strong> - the submission against {@code raw-event.schema.json}.</li>
 *   <li><strong>Payload</strong> - the body against the schema registered for the event type.</li>
 * </ol>
 *
 * <p>All three passes run even when an earlier one fails, so one round trip tells the caller
 * everything that is wrong rather than one thing at a time. Errors are capped at
 * {@link #MAX_ERRORS_REPORTED} so a wildly malformed payload cannot produce a megabyte of response.
 *
 * <p><strong>Unknown event types are not, by default, an error.</strong> Ingestion's contract is to
 * preserve facts for replay; the source-to-canonical mapping belongs to normalization-worker, which
 * dead-letters what it cannot map. Set {@code ingestion.schemas.fail-on-unknown-event-type=true} in
 * an environment where every adapter is expected to be registered up front.
 */
@Component
public class RawEventValidator {

    /** Enough detail to fix an adapter, not enough to write a novel. */
    public static final int MAX_ERRORS_REPORTED = 50;

    private static final Logger log = LoggerFactory.getLogger(RawEventValidator.class);

    private final SchemaRegistry registry;
    private final IngestionProperties properties;
    private final ObjectMapper mapper;

    public RawEventValidator(SchemaRegistry registry, IngestionProperties properties, ObjectMapper mapper) {
        this.registry = registry;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Validates one submission.
     *
     * @param request the submitted event; must not be null
     * @return a structured outcome, never null
     */
    public ValidationOutcome validate(IngestRequest request) {
        if (request == null) {
            return ValidationOutcome.invalid(null, RejectedEvent.MALFORMED_REQUEST,
                    List.of(FieldError.of("$", "event body is absent", "required")));
        }

        List<FieldError> errors = new ArrayList<>();
        List<FieldError> structural = structuralErrors(request);
        errors.addAll(structural);
        // The envelope schema restates the structural requirements; running it only when the cheap
        // checks pass keeps the response free of the same failure reported twice.
        if (structural.isEmpty()) {
            errors.addAll(envelopeErrors(request));
        }

        Optional<RegisteredSchema> payloadSchema = registry.findByEventType(request.sourceEventType());
        String schemaName = payloadSchema.map(RegisteredSchema::name).orElse(null);

        if (payloadSchema.isPresent()) {
            if (request.body() != null && request.body().isObject()) {
                errors.addAll(collect(payloadSchema.get().schema().validate(request.body()), "body"));
            }
        } else if (request.sourceEventType() != null && !request.sourceEventType().isBlank()) {
            if (properties.getSchemas().isFailOnUnknownEventType()) {
                errors.add(FieldError.of("sourceEventType",
                        "no JSON Schema is registered for source event type '" + request.sourceEventType()
                                + "'; register one in /schemas/events or add an ingestion.schemas.aliases entry",
                        RejectedEvent.UNKNOWN_SCHEMA));
            } else {
                log.debug("No schema registered for source event type '{}' from '{}'; accepting unvalidated "
                                + "(normalization-worker owns the mapping)",
                        request.sourceEventType(), request.sourceSystem());
            }
        }

        if (errors.isEmpty()) {
            return ValidationOutcome.valid(schemaName);
        }
        List<FieldError> capped = errors.size() <= MAX_ERRORS_REPORTED
                ? errors
                : new ArrayList<>(errors.subList(0, MAX_ERRORS_REPORTED));
        String code = capped.stream().anyMatch(e -> RejectedEvent.UNKNOWN_SCHEMA.equals(e.code()))
                ? RejectedEvent.UNKNOWN_SCHEMA
                : RejectedEvent.SCHEMA_VALIDATION_FAILED;
        return ValidationOutcome.invalid(schemaName, code, capped);
    }

    // --- passes ---------------------------------------------------------------------------

    private List<FieldError> structuralErrors(IngestRequest request) {
        List<FieldError> errors = new ArrayList<>(4);
        if (isBlank(request.sourceSystem())) {
            errors.add(FieldError.of("sourceSystem", "is required and must not be blank", "required"));
        }
        if (isBlank(request.sourceEventType())) {
            errors.add(FieldError.of("sourceEventType", "is required and must not be blank", "required"));
        }
        if (isBlank(request.merchantId())) {
            errors.add(FieldError.of("merchantId", "is required and must not be blank", "required"));
        }
        if (request.body() == null || request.body().isNull() || request.body().isMissingNode()) {
            errors.add(FieldError.of("body", "is required (alias: payload) and must be a JSON object", "required"));
        } else if (!request.body().isObject()) {
            errors.add(FieldError.of("body", "must be a JSON object, not " + request.body().getNodeType(), "type"));
        }
        return errors;
    }

    private List<FieldError> envelopeErrors(IngestRequest request) {
        if (!properties.getSchemas().isValidateEnvelope()) {
            return List.of();
        }
        Optional<JsonSchema> envelope = registry.envelopeSchema();
        if (envelope.isEmpty()) {
            log.debug("Envelope schema '{}' is not registered; relying on structural checks only",
                    SchemaRegistry.RAW_EVENT_SCHEMA);
            return List.of();
        }
        JsonNode tree = mapper.valueToTree(request);
        return collect(envelope.get().validate(tree), null);
    }

    // --- translation ----------------------------------------------------------------------

    /**
     * Turns networknt {@link ValidationMessage}s into {@link FieldError}s with a dotted path the
     * caller can act on. Sorted by path so a retry produces an identical response for identical
     * input - determinism matters even in error paths.
     */
    private static List<FieldError> collect(Set<ValidationMessage> messages, String prefix) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(m -> new FieldError(
                        fieldPath(prefix, instanceLocation(m)),
                        cleanMessage(m),
                        m.getType(),
                        schemaLocation(m)))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
    }

    private static String instanceLocation(ValidationMessage m) {
        return m.getInstanceLocation() == null ? "" : m.getInstanceLocation().toString();
    }

    private static String schemaLocation(ValidationMessage m) {
        return m.getSchemaLocation() == null ? null : m.getSchemaLocation().toString();
    }

    /**
     * {@code $.amount.currency} plus prefix {@code body} becomes {@code body.amount.currency};
     * the document root becomes the prefix itself, or {@code $} when there is none.
     */
    private static String fieldPath(String prefix, String instanceLocation) {
        String path = instanceLocation == null ? "" : instanceLocation.trim();
        if (path.startsWith("$")) {
            path = path.substring(1);
        }
        if (path.startsWith(".")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return prefix == null ? "$" : prefix;
        }
        return prefix == null ? path : prefix + (path.startsWith("[") ? "" : ".") + path;
    }

    /**
     * networknt prefixes messages with the instance location ("$.amount: is missing..."), which is
     * redundant once the location is its own field.
     */
    private static String cleanMessage(ValidationMessage m) {
        String message = m.getMessage() == null ? "" : m.getMessage().trim();
        String location = instanceLocation(m);
        if (!location.isEmpty() && message.startsWith(location)) {
            message = message.substring(location.length()).trim();
            if (message.startsWith(":")) {
                message = message.substring(1).trim();
            }
        }
        return message.isEmpty() ? m.getType() : message;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
