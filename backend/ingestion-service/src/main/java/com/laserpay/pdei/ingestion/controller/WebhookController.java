package com.laserpay.pdei.ingestion.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.ingestion.model.IngestBatchResult;
import com.laserpay.pdei.ingestion.model.IngestRequest;
import com.laserpay.pdei.ingestion.model.IngestResponse;
import com.laserpay.pdei.ingestion.security.WebhookSignatureVerifier;
import com.laserpay.pdei.ingestion.service.IngestionService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ingest/v1/events/{sourceSystem}/webhook} - source-specific webhook intake
 * (PLATFORM-CONTRACT section 8.2).
 *
 * <p>Separate from {@link IngestionController} for one concrete reason: HMAC verification must run
 * over the <em>exact bytes received</em>. Letting Spring bind the body to an object and
 * re-serialising it would change key order and whitespace and invalidate every signature, so this
 * controller takes {@code byte[]} and parses it itself, after verifying.
 *
 * <p><strong>Order of operations is the security property.</strong> Verify, then parse, then
 * ingest. Nothing is parsed, logged in full, or acted on before the signature check passes.
 *
 * <p><strong>Shape tolerance.</strong> Webhook bodies are whatever the source decided:
 * <ul>
 *   <li>a bare JSON object - one event;</li>
 *   <li>a JSON array - a batch, subject to the same {@code ingestion.batch.max-size} cap;</li>
 *   <li>an object with an {@code events} / {@code data} array - a batch with an outer wrapper.</li>
 * </ul>
 * The body is preserved verbatim as the raw event body in every case; ingestion never rewrites what
 * a source sent, because replay depends on it being the original.
 *
 * <p>Merchant and event type are read from the body ({@code merchantId}, {@code eventType} and
 * their common spellings) or from the configured headers when the source puts them there.
 */
@RestController
@RequestMapping(path = "/ingest/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /** Body fields checked, in order, for the source's own event type. */
    private static final List<String> EVENT_TYPE_FIELDS =
            List.of("sourceEventType", "eventType", "event_type", "type", "event");

    /** Body fields checked, in order, for the merchant. */
    private static final List<String> MERCHANT_FIELDS =
            List.of("merchantId", "merchant_id", "merchant");

    /** Body fields checked, in order, for the source's own delivery id. */
    private static final List<String> EVENT_ID_FIELDS =
            List.of("eventId", "event_id", "id", "deliveryId", "delivery_id");

    /** Wrapper fields that may hold an array of events. */
    private static final List<String> BATCH_FIELDS = List.of("events", "records", "data", "items");

    private final IngestionService ingestionService;
    private final WebhookSignatureVerifier verifier;
    private final IngestionProperties properties;
    private final ObjectMapper mapper;

    public WebhookController(IngestionService ingestionService,
                             WebhookSignatureVerifier verifier,
                             IngestionProperties properties,
                             ObjectMapper mapper) {
        this.ingestionService = ingestionService;
        this.verifier = verifier;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Accepts a signed webhook delivery from one source system.
     *
     * @param sourceSystem   path segment identifying the caller; also the key into
     *                       {@code ingestion.webhook.secrets}
     * @param body           raw request bytes, verified before being parsed
     * @param headers        all request headers, read for the configured signature/timestamp/hint names
     * @param idempotencyKey optional {@code Idempotency-Key}
     * @param traceparent    optional W3C trace context
     * @return 202 with {@code {accepted, rejected[], duplicates}}
     */
    @PostMapping(path = "/events/{sourceSystem}/webhook")
    public ResponseEntity<IngestResponse> webhook(
            @PathVariable String sourceSystem,
            @RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(name = IngestionController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = "traceparent", required = false) String traceparent) {

        IngestionProperties.Webhook config = properties.getWebhook();
        verifier.verify(sourceSystem,
                body,
                headers.getFirst(config.getSignatureHeader()),
                headers.getFirst(config.getTimestampHeader()));

        JsonNode parsed = parse(sourceSystem, body);
        List<IngestRequest> requests = toRequests(sourceSystem, parsed, headers);
        log.debug("Webhook from '{}' carried {} event(s)", sourceSystem, requests.size());

        IngestBatchResult result = requests.size() == 1
                ? ingestionService.ingestOne(requests.get(0), idempotencyKey, traceparent)
                : ingestionService.ingestBatch(requests, idempotencyKey, traceparent);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        String eventId = requests.size() == 1 ? result.firstEventId() : null;
        if (eventId != null) {
            response.header(IngestionController.RAW_EVENT_ID_HEADER, eventId);
        }
        return response.body(result.response());
    }

    // --- parsing --------------------------------------------------------------------------

    private JsonNode parse(String sourceSystem, byte[] body) {
        if (body == null || body.length == 0) {
            throw new ValidationException("Webhook body from '" + sourceSystem + "' is empty");
        }
        try {
            return mapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new ValidationException(
                    "Webhook body from '" + sourceSystem + "' is not valid JSON", e);
        }
    }

    /** Flattens whatever shape the source used into one or more submissions. */
    private List<IngestRequest> toRequests(String sourceSystem, JsonNode parsed, HttpHeaders headers) {
        if (parsed.isArray()) {
            return fromArray(sourceSystem, parsed, headers);
        }
        if (parsed.isObject()) {
            for (String field : BATCH_FIELDS) {
                JsonNode candidate = parsed.get(field);
                if (candidate != null && candidate.isArray() && !candidate.isEmpty()) {
                    return fromArray(sourceSystem, candidate, headers);
                }
            }
            return List.of(toRequest(sourceSystem, parsed, headers));
        }
        throw new ValidationException("Webhook body from '" + sourceSystem
                + "' must be a JSON object or array, not " + parsed.getNodeType());
    }

    private List<IngestRequest> fromArray(String sourceSystem, JsonNode array, HttpHeaders headers) {
        List<IngestRequest> requests = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            if (!element.isObject()) {
                throw new ValidationException("Webhook batch from '" + sourceSystem
                        + "' contains a non-object element");
            }
            requests.add(toRequest(sourceSystem, element, headers));
        }
        if (requests.isEmpty()) {
            throw new ValidationException("Webhook batch from '" + sourceSystem + "' is empty");
        }
        return requests;
    }

    /**
     * Builds a submission from one source event object. The whole object becomes the raw body -
     * ingestion does not unwrap a source's {@code data} envelope, because normalization-worker
     * needs to see exactly what arrived.
     */
    private IngestRequest toRequest(String sourceSystem, JsonNode node, HttpHeaders headers) {
        IngestionProperties.Webhook config = properties.getWebhook();
        String eventType = firstText(node, EVENT_TYPE_FIELDS, headers.getFirst(config.getEventTypeHeader()));
        String merchantId = firstText(node, MERCHANT_FIELDS, headers.getFirst(config.getMerchantHeader()));
        String sourceEventId = firstText(node, EVENT_ID_FIELDS, null);

        return new IngestRequest(
                sourceEventId,
                sourceSystem,
                eventType,
                merchantId,
                null,                 // resolved from the body by AggregateIdResolver
                headers.getFirst("X-Correlation-Id"),
                null,
                null,                 // occurredAt stays inside the body; normalisation extracts it
                sourceEventId,        // the source's own id is the natural idempotency key
                transportHeaders(headers),
                node);
    }

    /**
     * The handful of transport headers worth preserving on the envelope. Deliberately an allowlist:
     * copying every header would put the signature and any bearer token onto a Kafka topic that is
     * replayed, exported and inspected.
     */
    private Map<String, String> transportHeaders(HttpHeaders headers) {
        Map<String, String> preserved = new LinkedHashMap<>();
        putIfPresent(preserved, headers, HttpHeaders.CONTENT_TYPE);
        putIfPresent(preserved, headers, HttpHeaders.USER_AGENT);
        putIfPresent(preserved, headers, properties.getWebhook().getTimestampHeader());
        putIfPresent(preserved, headers, properties.getWebhook().getEventTypeHeader());
        return preserved;
    }

    private static void putIfPresent(Map<String, String> sink, HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value != null && !value.isBlank()) {
            sink.put(name.toLowerCase(java.util.Locale.ROOT), value);
        }
    }

    private static String firstText(JsonNode node, List<String> fields, String fallback) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return fallback == null || fallback.isBlank() ? null : fallback;
    }
}
