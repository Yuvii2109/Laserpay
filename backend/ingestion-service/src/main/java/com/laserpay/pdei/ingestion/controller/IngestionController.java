package com.laserpay.pdei.ingestion.controller;

import com.laserpay.pdei.ingestion.metrics.IngestionMetrics;
import com.laserpay.pdei.ingestion.model.IngestBatchResult;
import com.laserpay.pdei.ingestion.model.IngestRequest;
import com.laserpay.pdei.ingestion.model.IngestResponse;
import com.laserpay.pdei.ingestion.model.IngestionStats;
import com.laserpay.pdei.ingestion.model.SchemaDescriptor;
import com.laserpay.pdei.ingestion.service.IngestionService;
import com.laserpay.pdei.ingestion.validation.SchemaRegistry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ingestion REST surface, exactly as PLATFORM-CONTRACT section 8.2 defines it, based at
 * {@code http://localhost:8081/ingest/v1}:
 *
 * <pre>
 * POST /events            single raw event  (header: Idempotency-Key)
 * POST /events/batch      array, max 1000
 * GET  /schemas           registered source schemas
 * GET  /stats             accepted/rejected/deduped counters
 * </pre>
 *
 * <p>{@code POST /events/{sourceSystem}/webhook} is the fifth route of that section and lives in
 * {@link WebhookController}, because it needs the raw request bytes for HMAC verification and must
 * not share this controller's parsed-body binding.
 *
 * <p><strong>Why everything returns 202.</strong> The contract says {@code 202 Accepted} with
 * {@code {accepted, rejected[], duplicates}}. That status describes the <em>request</em>: it was
 * well formed and has been processed. Individual events that failed validation are reported inside
 * the body, not by the status code, so a batch is never all-or-nothing. A 4xx here means the request
 * itself was wrong - unparseable JSON, or a batch over the cap.
 *
 * <p><strong>Idempotency-Key.</strong> Honoured on both write routes. On {@code /events} it is the
 * dedupe identity of the single fact. On {@code /events/batch} it is a prefix, suffixed with the
 * array index, so one header makes an entire batch safely retryable without collapsing 1000 distinct
 * events onto one key.
 */
@RestController
@RequestMapping(path = "/ingest/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class IngestionController {

    /** Response header carrying the id assigned to a single accepted event. */
    public static final String RAW_EVENT_ID_HEADER = "X-PDEI-Raw-Event-Id";

    /** Standard idempotency header (PLATFORM-CONTRACT section 8.2). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IngestionService ingestionService;
    private final SchemaRegistry schemaRegistry;
    private final IngestionMetrics metrics;

    public IngestionController(IngestionService ingestionService,
                               SchemaRegistry schemaRegistry,
                               IngestionMetrics metrics) {
        this.ingestionService = ingestionService;
        this.schemaRegistry = schemaRegistry;
        this.metrics = metrics;
    }

    /**
     * Accepts one raw source event.
     *
     * @param request        the event; see {@code schemas/events/raw-event.schema.json}
     * @param idempotencyKey optional stable key identifying the fact
     * @param traceparent    optional W3C trace context, propagated onto the Kafka record
     * @return 202 with {@code {accepted, rejected[], duplicates}}
     */
    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestEvent(
            @RequestBody IngestRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = "traceparent", required = false) String traceparent) {

        IngestBatchResult result = ingestionService.ingestOne(request, idempotencyKey, traceparent);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        String eventId = result.firstEventId();
        if (eventId != null) {
            response.header(RAW_EVENT_ID_HEADER, eventId);
        }
        return response.body(result.response());
    }

    /**
     * Accepts up to {@code ingestion.batch.max-size} (default 1000) raw source events.
     *
     * <p>Over the cap is a 400, not a truncation: an adapter that oversends must find out, and
     * silently dropping the tail of a financial event batch is not a behaviour this platform will
     * have.
     */
    @PostMapping(path = "/events/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingestBatch(
            @RequestBody List<IngestRequest> requests,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = "traceparent", required = false) String traceparent) {

        IngestBatchResult result = ingestionService.ingestBatch(requests, idempotencyKey, traceparent);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.response());
    }

    /**
     * The registered source schemas: what ingestion will validate a payload against, where each
     * schema was loaded from, and which canonical event type it maps to.
     */
    @GetMapping("/schemas")
    public List<SchemaDescriptor> schemas() {
        return schemaRegistry.descriptors();
    }

    /**
     * Process-lifetime accepted/rejected/deduped counters. The authoritative time series is
     * {@code /actuator/prometheus}; this exists for a quick human answer and for the demo console.
     */
    @GetMapping("/stats")
    public IngestionStats stats() {
        return metrics.snapshot(schemaRegistry.size());
    }
}
