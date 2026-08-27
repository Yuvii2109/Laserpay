package com.laserpay.pdei.audit.controller;

import com.laserpay.pdei.audit.chain.ChainVerificationReport;
import com.laserpay.pdei.audit.chain.ChainVerifier;
import com.laserpay.pdei.audit.config.AuditProperties;
import com.laserpay.pdei.audit.metrics.AuditMetrics;
import com.laserpay.pdei.audit.repository.AuditEventStore;
import com.laserpay.pdei.audit.repository.AuditQuery;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The audit read API, PLATFORM-CONTRACT section 8.4, base {@code http://localhost:8087/audit/v1}:
 *
 * <pre>
 *   GET /events        ?entityType&amp;entityId&amp;actor&amp;from&amp;to
 *   GET /chain/verify   recompute the hash chain, return the first divergence if any
 *   GET /export         NDJSON export
 * </pre>
 *
 * <p>The base path is declared here rather than as a servlet context path so that
 * {@code /actuator/health} and {@code /actuator/prometheus} stay at the paths the contract fixes for
 * every Spring service (section 2).
 *
 * <p><strong>Read-only by construction.</strong> There is no POST, PUT, PATCH or DELETE mapping in
 * this class, and there is no write method anywhere behind it: {@link AuditEventStore} exposes only
 * appends, and appends happen from Kafka. The audit trail cannot be edited through its own API.
 */
@RestController
@RequestMapping("/audit/v1")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    /** One JSON document per line, newline separated (https://ndjson.org). */
    private static final String NDJSON_VALUE = "application/x-ndjson";

    private final AuditEventStore store;
    private final ChainVerifier verifier;
    private final AuditProperties properties;
    private final AuditMetrics metrics;
    private final Clocks clock;

    public AuditController(AuditEventStore store, ChainVerifier verifier, AuditProperties properties,
                           AuditMetrics metrics, Clocks clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * {@code GET /audit/v1/events} - filtered, paged history.
     *
     * <p>Ordered newest first, because the question is almost always "what just happened to this
     * thing". Chain <em>verification</em> walks the opposite direction, in {@code sequence_no}
     * order, since that is the direction the hashes link.
     */
    @GetMapping("/events")
    public AuditPageResponse events(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {

        AuditQuery query = new AuditQuery(entityType, entityId, merchantId, actor, action, from, to,
                Math.max(0, page), pageSize(size));

        List<AuditEvent> events = store.find(query);
        long total = store.count(query);
        return new AuditPageResponse(AuditEventResponse.from(events), query.page(), query.size(), total);
    }

    /**
     * {@code GET /audit/v1/chain/verify} - recompute one merchant chain, or every chain when no
     * merchant is given.
     *
     * <p>Always {@code 200}, even when the chain is broken. A broken chain is a successful answer to
     * the question that was asked; returning {@code 500} would make a monitoring system report an
     * outage of the audit service rather than the far more serious fact it just discovered.
     */
    @GetMapping("/chain/verify")
    public ChainVerifyResponse verifyChain(
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "1000") int maxChains) {

        long startNanos = System.nanoTime();
        List<ChainVerificationReport> reports = merchantId == null || merchantId.isBlank()
                ? verifyEveryChain(maxChains)
                : List.of(verifier.verify(merchantId));

        boolean intact = reports.stream().allMatch(ChainVerificationReport::intact);
        if (metrics != null) {
            metrics.chainVerified(intact, System.nanoTime() - startNanos);
            metrics.brokenChains((int) reports.stream().filter(r -> !r.intact()).count());
        }
        if (!intact) {
            log.error("audit chain verification FAILED for {} of {} chains",
                    reports.stream().filter(r -> !r.intact()).count(), reports.size());
        }
        return ChainVerifyResponse.of(reports, clock.now());
    }

    /**
     * {@code GET /audit/v1/export} - the whole filtered history as NDJSON, one entry per line.
     *
     * <p>Streamed through {@link StreamingResponseBody} rather than materialised: an export is
     * routinely the entire history of a merchant, which is exactly the payload that must not be
     * assembled in memory first. The rows are fetched in keyset batches, written, and flushed, so
     * peak memory is one batch regardless of whether the client asked for ten entries or a million.
     *
     * <p>NDJSON rather than a JSON array for the same reason on the client side: a consumer can
     * process line by line and can stop early, and a truncated transfer leaves whole valid records
     * rather than one unparseable document.
     */
    @GetMapping(value = "/export", produces = NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long limit) {

        AuditQuery query = new AuditQuery(entityType, entityId, merchantId, actor, action, from, to,
                0, properties.getApi().getExportBatchSize());
        long maxEvents = exportLimit(limit);

        StreamingResponseBody body = outputStream -> streamNdjson(query, maxEvents, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportFilename(merchantId) + "\"")
                // The body is generated as it is sent, so no intermediary may cache or transform it.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private void streamNdjson(AuditQuery query, long maxEvents, OutputStream outputStream) {
        long emitted = 0L;
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            emitted = store.stream(query, properties.getApi().getExportBatchSize(), maxEvents,
                    event -> writeLine(writer, event));
            writer.flush();
        } catch (IOException e) {
            // The client hung up mid-export. Normal, not an error worth a stack trace.
            log.debug("audit export stream closed after {} entries: {}", emitted, e.toString());
        } catch (UncheckedIOException e) {
            log.debug("audit export stream interrupted after {} entries: {}", emitted, e.toString());
        } finally {
            if (metrics != null) {
                metrics.exported(emitted);
            }
        }
    }

    /**
     * One entry, one line.
     *
     * <p>{@code Json.write} never emits a raw newline (Jackson escapes them inside strings and this
     * mapper does not pretty-print), so the line framing cannot be broken by content.
     */
    private static void writeLine(Writer writer, AuditEvent event) {
        try {
            writer.write(Json.write(AuditEventResponse.from(event)));
            writer.write('\n');
        } catch (IOException e) {
            // Unwrapped by streamNdjson; ends the export cleanly instead of half-writing a line.
            throw new UncheckedIOException(e);
        }
    }

    private List<ChainVerificationReport> verifyEveryChain(int maxChains) {
        List<String> chains = store.findChainKeys(Math.max(1, maxChains));
        return chains.stream().map(verifier::verify).toList();
    }

    private int pageSize(Integer requested) {
        AuditProperties.Api api = properties.getApi();
        if (requested == null || requested < 1) {
            return api.getDefaultPageSize();
        }
        return Math.min(requested, api.getMaxPageSize());
    }

    private long exportLimit(Long requested) {
        long max = properties.getApi().getMaxExportEvents();
        if (requested == null || requested < 1) {
            return max;
        }
        return Math.min(requested, max);
    }

    private static String exportFilename(String merchantId) {
        String scope = merchantId == null || merchantId.isBlank() ? "all" : merchantId;
        return "pdei-audit-" + scope + ".ndjson";
    }
}
