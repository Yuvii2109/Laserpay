package com.laserpay.pdei.docproc.service;

import com.laserpay.pdei.common.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counters behind {@code GET /docproc/v1/stats} and the Prometheus scrape.
 *
 * <p>Two sinks on purpose. Micrometer carries the platform-wide series
 * ({@code pdei_events_processed_total}, {@code pdei_events_duplicate_total},
 * {@code pdei_event_processing_latency_seconds}) that Grafana dashboards join across services;
 * the plain atomics here back the service's own {@code /stats} endpoint, which has to answer
 * "what has this worker done since it started" without a Prometheus query.
 */
@Component
public class DocProcStats {

    /** {@code service} tag value used on every platform metric emitted by this module. */
    public static final String SERVICE = "document-processor-service";

    private final MeterRegistry meterRegistry;

    private final AtomicLong eventsReceived = new AtomicLong();
    private final AtomicLong eventsDuplicate = new AtomicLong();
    private final AtomicLong eventsSkippedSelfEmitted = new AtomicLong();
    private final AtomicLong extracted = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong quarantined = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong bytesProcessed = new AtomicLong();
    private final AtomicLong charactersIndexed = new AtomicLong();
    private final AtomicLong pagesProcessed = new AtomicLong();
    private final AtomicLong integrityMismatches = new AtomicLong();
    private final AtomicReference<Instant> lastProcessedAt = new AtomicReference<>();
    private final Map<String, AtomicLong> byExtractor = new ConcurrentHashMap<>();

    public DocProcStats(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("pdei_docproc_characters_indexed_total", charactersIndexed, AtomicLong::get);
        meterRegistry.gauge("pdei_docproc_bytes_processed_total", bytesProcessed, AtomicLong::get);
    }

    public void eventReceived() {
        eventsReceived.incrementAndGet();
    }

    /** A redelivery or replay that the idempotency claim rejected. */
    public void eventDuplicate() {
        eventsDuplicate.incrementAndGet();
        meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL,
                MetricNames.Tag.SERVICE, SERVICE).increment();
    }

    /** An EVIDENCE event this service published itself; ignored to avoid a feedback loop. */
    public void eventSelfEmitted() {
        eventsSkippedSelfEmitted.incrementAndGet();
    }

    /** Records a completed attempt against both sinks. */
    public void recordOutcome(ProcessingOutcome outcome, String eventType, Duration elapsed) {
        switch (outcome.status()) {
            case EXTRACTED -> {
                extracted.incrementAndGet();
                charactersIndexed.addAndGet(outcome.characters());
                pagesProcessed.addAndGet(outcome.pageCount());
                if (outcome.extractor() != null) {
                    byExtractor.computeIfAbsent(outcome.extractor(), key -> new AtomicLong())
                            .incrementAndGet();
                }
            }
            case QUARANTINED -> quarantined.incrementAndGet();
            case SKIPPED_UNCHANGED, SKIPPED_NO_OBJECT, NOT_FOUND -> skipped.incrementAndGet();
        }
        if (!outcome.integrityOk()) {
            integrityMismatches.incrementAndGet();
        }
        lastProcessedAt.set(outcome.at());

        meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, SERVICE,
                MetricNames.Tag.TYPE, eventType,
                MetricNames.Tag.OUTCOME, outcome.outcomeTag()).increment();

        if (elapsed != null) {
            Timer.builder(MetricNames.EVENT_PROCESSING_LATENCY_SECONDS)
                    .tag(MetricNames.Tag.SERVICE, SERVICE)
                    .tag(MetricNames.Tag.TYPE, eventType)
                    .register(meterRegistry)
                    .record(elapsed);
        }
    }

    /** An attempt that threw before producing an outcome. */
    public void recordFailure(String eventType) {
        failed.incrementAndGet();
        meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, SERVICE,
                MetricNames.Tag.TYPE, eventType,
                MetricNames.Tag.OUTCOME, MetricNames.Outcome.FAILURE).increment();
    }

    public void bytesRead(long bytes) {
        bytesProcessed.addAndGet(bytes);
    }

    /** Immutable snapshot for the {@code /stats} response. */
    public Snapshot snapshot() {
        Map<String, Long> extractors = new java.util.TreeMap<>();
        byExtractor.forEach((name, count) -> extractors.put(name, count.get()));
        return new Snapshot(
                eventsReceived.get(), eventsDuplicate.get(), eventsSkippedSelfEmitted.get(),
                extracted.get(), skipped.get(), quarantined.get(), failed.get(),
                bytesProcessed.get(), charactersIndexed.get(), pagesProcessed.get(),
                integrityMismatches.get(), lastProcessedAt.get(), Map.copyOf(extractors));
    }

    /**
     * Point-in-time counters.
     *
     * @param eventsReceived          EVIDENCE events consumed from Kafka
     * @param eventsDuplicate         rejected by the idempotency claim
     * @param eventsSelfEmitted       ignored because this service published them
     * @param extracted               artifacts whose text reached the FTS column
     * @param skipped                 unchanged, artifact-less or unknown evidence
     * @param quarantined             artifacts that could not be used
     * @param failed                  attempts that threw
     * @param bytesProcessed          bytes read from the object store
     * @param charactersIndexed       characters written into {@code evidence.extracted_text}
     * @param pagesProcessed          PDF pages read
     * @param integrityMismatches     recomputed sha256 disagreeing with the recorded one
     * @param lastProcessedAt         end of the most recent attempt, null when idle since start
     * @param extractionsByExtractor  successful extractions per extractor name
     */
    public record Snapshot(long eventsReceived,
                           long eventsDuplicate,
                           long eventsSelfEmitted,
                           long extracted,
                           long skipped,
                           long quarantined,
                           long failed,
                           long bytesProcessed,
                           long charactersIndexed,
                           long pagesProcessed,
                           long integrityMismatches,
                           Instant lastProcessedAt,
                           Map<String, Long> extractionsByExtractor) {
    }
}
