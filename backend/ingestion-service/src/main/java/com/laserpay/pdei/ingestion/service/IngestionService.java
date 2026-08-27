package com.laserpay.pdei.ingestion.service;

import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.ingestion.dedupe.IdempotencyService;
import com.laserpay.pdei.ingestion.metrics.IngestionMetrics;
import com.laserpay.pdei.ingestion.model.IngestBatchResult;
import com.laserpay.pdei.ingestion.model.IngestRequest;
import com.laserpay.pdei.ingestion.model.IngestResponse;
import com.laserpay.pdei.ingestion.model.RejectedEvent;
import com.laserpay.pdei.ingestion.publisher.RawEventPublisher;
import com.laserpay.pdei.ingestion.validation.ValidationOutcome;
import com.laserpay.pdei.ingestion.validation.RawEventValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The ingestion pipeline: validate, deduplicate, publish. One place, three steps, in that order.
 *
 * <p>Every submitted event is processed independently. A batch of 500 where three fail schema
 * validation still publishes 497 - partial success is the correct behaviour for an event intake,
 * because an adapter's bug in one event is not a reason to lose the other 497 facts. The response
 * accounts for every submitted event exactly once:
 * {@code accepted + duplicates + rejected.size() == submitted}.
 *
 * <h2>Identity</h2>
 *
 * <p>Two identifiers matter and they are not the same thing:
 * <ul>
 *   <li>{@code rawEventId} identifies this <em>delivery</em>. Derived deterministically from the
 *       idempotency key when the submitter does not supply one, so a retry produces the same id
 *       rather than a fresh one that would look like a new fact.</li>
 *   <li>{@code idempotencyKey} identifies this <em>fact</em>, and is what dedupe claims. Precedence:
 *       the HTTP {@code Idempotency-Key} header, then the body's {@code idempotencyKey}, then
 *       {@code rawEventId}, and finally - when the submitter gave nothing at all - a SHA-256 over
 *       the canonical (source, type, merchant, body) tuple, so an adapter with no idempotency story
 *       still gets one.</li>
 * </ul>
 *
 * <p>Because the key defaults to the event id, the Redis key is literally
 * {@code pdei:idem:{eventId}} in the ordinary case, exactly as PLATFORM-CONTRACT section 12
 * specifies, while a caller-supplied header is still honoured.
 *
 * <h2>Ordering and lateness</h2>
 *
 * <p>{@code occurredAt} (when the fact happened) and {@code receivedAt} (when PDEI saw it) are both
 * preserved and never collapsed - the gap between them is lateness, and downstream needs it. Nothing
 * here rejects an old event: assuming late and out-of-order delivery is rule 10.
 */
@Service
public class IngestionService {

    /**
     * Routing hints written into {@code RawEventEnvelope.headers} for normalization-worker. The
     * envelope's header map is free-form source metadata; these {@code pdei-} entries are the
     * platform's own additions and are documented as an outbound contract in this module's
     * context.md. {@code pdei-event-id}, {@code pdei-merchant-id} and {@code pdei-correlation-id}
     * are the Kafka header names from {@code EventHeaders} reused verbatim so there is one
     * vocabulary, not two.
     */
    public static final String HEADER_AGGREGATE_ID = "pdei-aggregate-id";
    public static final String HEADER_CAUSATION_ID = "pdei-causation-id";
    public static final String HEADER_OCCURRED_AT = "pdei-occurred-at";
    public static final String HEADER_RECEIVED_AT = "pdei-received-at";
    public static final String HEADER_SOURCE_SYSTEM = "pdei-source-system";
    public static final String HEADER_SCHEMA = "pdei-schema";

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final RawEventValidator validator;
    private final IdempotencyService idempotency;
    private final RawEventPublisher publisher;
    private final IngestionMetrics metrics;
    private final IngestionProperties properties;
    private final Clocks clock;

    public IngestionService(RawEventValidator validator,
                            IdempotencyService idempotency,
                            RawEventPublisher publisher,
                            IngestionMetrics metrics,
                            IngestionProperties properties,
                            Clocks clock) {
        this.validator = validator;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Ingests one event.
     *
     * @param request        the submission
     * @param idempotencyKey value of the {@code Idempotency-Key} header, or null
     * @param traceparent    inbound W3C trace context, or null
     */
    public IngestBatchResult ingestOne(IngestRequest request, String idempotencyKey, String traceparent) {
        return ingest(List.of(new IndexedRequest(0, request)), idempotencyKey, traceparent, false);
    }

    /**
     * Ingests a batch.
     *
     * @param requests       the submissions, in order
     * @param idempotencyKey value of the {@code Idempotency-Key} header, or null. For a batch it is
     *                       used as a <em>prefix</em>, suffixed with the array index, so one header
     *                       makes the whole batch replay-safe without collapsing distinct events
     *                       onto a single key
     * @param traceparent    inbound W3C trace context, or null
     * @throws ValidationException when the batch exceeds {@code ingestion.batch.max-size}
     */
    public IngestBatchResult ingestBatch(List<IngestRequest> requests, String idempotencyKey, String traceparent) {
        List<IngestRequest> safe = requests == null ? List.of() : requests;
        int max = properties.getBatch().getMaxSize();
        if (safe.size() > max) {
            throw new ValidationException(
                    "Batch of " + safe.size() + " exceeds the maximum of " + max + " events per request",
                    Map.of("submitted", safe.size(), "maxSize", max));
        }
        List<IndexedRequest> indexed = new ArrayList<>(safe.size());
        for (int i = 0; i < safe.size(); i++) {
            indexed.add(new IndexedRequest(i, safe.get(i)));
        }
        return ingest(indexed, idempotencyKey, traceparent, true);
    }

    // --- pipeline -------------------------------------------------------------------------

    private IngestBatchResult ingest(List<IndexedRequest> requests,
                                     String idempotencyKeyHeader,
                                     String traceparent,
                                     boolean batch) {
        int accepted = 0;
        int duplicates = 0;
        List<RejectedEvent> rejected = new ArrayList<>();
        List<String> eventIds = new ArrayList<>(requests.size());

        for (IndexedRequest indexed : requests) {
            String headerKey = keyForIndex(idempotencyKeyHeader, indexed.index(), batch);
            Outcome outcome = process(indexed.index(), indexed.request(), headerKey, traceparent);
            eventIds.add(outcome.rawEventId());
            switch (outcome.status()) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected.add(outcome.rejected());
                default -> throw new IllegalStateException("Unhandled outcome " + outcome.status());
            }
        }

        if (!rejected.isEmpty() || duplicates > 0) {
            log.info("Ingested {} event(s): accepted={} duplicates={} rejected={}",
                    requests.size(), accepted, duplicates, rejected.size());
        }
        return new IngestBatchResult(new IngestResponse(accepted, rejected, duplicates), eventIds);
    }

    private Outcome process(int index, IngestRequest request, String headerKey, String traceparent) {
        long startNanos = System.nanoTime();

        ValidationOutcome validation = validator.validate(request);
        String metricType = validation.schemaName();
        if (!validation.valid()) {
            metrics.recordRejected(request == null ? null : request.sourceSystem(), metricType);
            log.warn("Rejected event at index {} from '{}': {}", index,
                    request == null ? "?" : request.sourceSystem(), validation.summary());
            return Outcome.rejected(null, new RejectedEvent(index,
                    request == null ? null : request.rawEventId(),
                    request == null ? null : request.sourceSystem(),
                    request == null ? null : request.sourceEventType(),
                    validation.code(), validation.summary(), validation.errors()));
        }

        Instant receivedAt = clock.now();
        String idempotencyKey;
        String rawEventId;
        String correlationId;
        String aggregateId;
        RawEventEnvelope envelope;
        try {
            idempotencyKey = resolveIdempotencyKey(request, headerKey);
            rawEventId = resolveRawEventId(request, idempotencyKey);
            correlationId = firstPresent(request.correlationId(), rawEventId);
            aggregateId = AggregateIdResolver.resolve(request);
            envelope = new RawEventEnvelope(
                    rawEventId,
                    request.sourceSystem(),
                    request.sourceEventType(),
                    request.merchantId(),
                    receivedAt,
                    idempotencyKey,
                    buildHeaders(request, aggregateId, correlationId, receivedAt, traceparent,
                            validation.schemaName()),
                    request.body());
        } catch (RuntimeException e) {
            // Building the envelope should be impossible to fail after validation, but one event in a
            // batch of a thousand must never be able to fail the other 999. Report it and move on.
            metrics.recordRejected(request.sourceSystem(), metricType);
            log.error("Could not build a raw envelope for the event at index {} from '{}'",
                    index, request.sourceSystem(), e);
            return Outcome.rejected(request.rawEventId(), new RejectedEvent(index, request.rawEventId(),
                    request.sourceSystem(), request.sourceEventType(), RejectedEvent.MALFORMED_REQUEST,
                    "could not build a raw event envelope: " + e.getMessage(), List.of()));
        }

        if (idempotency.claim(idempotencyKey) == IdempotencyService.Decision.DUPLICATE) {
            metrics.recordDuplicate(request.sourceSystem(), metricType);
            log.debug("Suppressed duplicate event {} (key {}) from '{}'",
                    rawEventId, idempotencyKey, request.sourceSystem());
            return Outcome.duplicate(rawEventId);
        }

        try {
            publisher.publish(envelope, aggregateId, correlationId, traceparent);
        } catch (RuntimeException e) {
            // The fact never reached the topic, so the claim must not survive: a retry of the same
            // submission has to be treated as new, not as a duplicate of a publication that failed.
            idempotency.release(idempotencyKey);
            metrics.recordDeadLettered(request.sourceSystem(), metricType);
            return Outcome.rejected(rawEventId, new RejectedEvent(index, rawEventId,
                    request.sourceSystem(), request.sourceEventType(), RejectedEvent.PUBLISH_FAILED,
                    "could not publish to pdei.raw.events.v1: " + e.getMessage(), List.of()));
        }

        metrics.recordAccepted(request.sourceSystem(), metricType);
        metrics.recordLatency(metricType, Duration.ofNanos(System.nanoTime() - startNanos));
        return Outcome.accepted(rawEventId);
    }

    // --- identity -------------------------------------------------------------------------

    /**
     * The key dedupe claims. See the class javadoc for the precedence rules; the content-hash last
     * resort means an adapter that supplies no identity at all still cannot double-book a fact by
     * retrying.
     */
    String resolveIdempotencyKey(IngestRequest request, String headerKey) {
        String explicit = firstPresent(headerKey, request.idempotencyKey(), request.rawEventId());
        if (explicit != null) {
            return explicit;
        }
        String canonical = request.sourceSystem() + "|" + request.sourceEventType() + "|"
                + request.merchantId() + "|" + Json.canonical(request.body());
        return Hashes.sha256Hex(canonical);
    }

    /**
     * The id of this delivery. Derived deterministically (UUID v3 over the merchant-scoped
     * idempotency key) when absent, so a retried submission carries the same id and the audit trail
     * stays honest about it being one fact, not two.
     */
    String resolveRawEventId(IngestRequest request, String idempotencyKey) {
        String supplied = firstPresent(request.rawEventId());
        if (supplied != null) {
            return supplied;
        }
        String seed = "pdei:raw:" + request.merchantId() + ":" + idempotencyKey;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The envelope's header map: whatever the source sent, plus the routing hints normalization-worker
     * needs to build a {@code CanonicalEvent} without re-deriving them from the body.
     */
    private Map<String, String> buildHeaders(IngestRequest request,
                                             String aggregateId,
                                             String correlationId,
                                             Instant receivedAt,
                                             String traceparent,
                                             String schemaName) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.headers() != null) {
            request.headers().forEach((k, v) -> {
                if (k != null && v != null) {
                    headers.put(k, v);
                }
            });
        }
        headers.put(HEADER_SOURCE_SYSTEM, request.sourceSystem());
        headers.put(EventHeaders.MERCHANT_ID, request.merchantId());
        headers.put(EventHeaders.CORRELATION_ID, correlationId);
        headers.put(HEADER_RECEIVED_AT, receivedAt.toString());
        if (aggregateId != null) {
            headers.put(HEADER_AGGREGATE_ID, aggregateId);
        }
        if (request.causationId() != null && !request.causationId().isBlank()) {
            headers.put(HEADER_CAUSATION_ID, request.causationId());
        }
        if (request.occurredAt() != null) {
            headers.put(HEADER_OCCURRED_AT, request.occurredAt().toString());
        }
        if (schemaName != null) {
            headers.put(HEADER_SCHEMA, schemaName);
        }
        if (traceparent != null && !traceparent.isBlank()) {
            headers.put(EventHeaders.TRACEPARENT, traceparent);
        }
        return headers;
    }

    /**
     * A batch shares one {@code Idempotency-Key} header across many distinct facts, so the index is
     * appended. Without it, submitting a batch of 100 would claim one key and suppress 99 events.
     */
    private static String keyForIndex(String header, int index, boolean batch) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return batch ? header + ":" + index : header;
    }

    private static String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    // --- internals ------------------------------------------------------------------------

    private record IndexedRequest(int index, IngestRequest request) {
    }

    private enum Status {
        ACCEPTED, DUPLICATE, REJECTED
    }

    private record Outcome(Status status, String rawEventId, RejectedEvent rejected) {

        static Outcome accepted(String rawEventId) {
            return new Outcome(Status.ACCEPTED, rawEventId, null);
        }

        static Outcome duplicate(String rawEventId) {
            return new Outcome(Status.DUPLICATE, rawEventId, null);
        }

        static Outcome rejected(String rawEventId, RejectedEvent rejected) {
            return new Outcome(Status.REJECTED, rawEventId, rejected);
        }
    }
}
