package com.laserpay.pdei.audit.metrics;

import com.laserpay.pdei.common.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer instrumentation for the audit service.
 *
 * <p>Contract metrics ({@link MetricNames}, PLATFORM-CONTRACT section 13) plus a small set of
 * audit-specific ones. The audit-specific counters exist because two things about this service are
 * invisible otherwise and both matter operationally:
 *
 * <ul>
 *   <li>{@link #CHAIN_CONFLICTS_TOTAL} - how often two writers raced for the same chain link. A
 *       steadily rising number means the lock is not doing its job and appends are being serialised
 *       by constraint violations instead.</li>
 *   <li>{@link #CHAIN_VERIFICATIONS_TOTAL} with {@code result="broken"} - the only automated signal
 *       that someone altered history. It should be flat at zero, forever, and an alert on any
 *       increment is the point of building a hash chain at all.</li>
 * </ul>
 *
 * <p>Tag cardinality stays bounded: entity types are an enum, results are a two-value vocabulary.
 * No merchant id, audit id or correlation id is ever a tag.
 */
public class AuditMetrics {

    /** Value of the {@code service} tag on every counter this class registers. */
    public static final String SERVICE = "audit-service";

    /** Counter, tags: entityType, sealed. Entries appended to a chain. */
    public static final String ENTRIES_APPENDED_TOTAL = "pdei_audit_entries_appended_total";

    /** Counter. Appends that lost the race for a chain link and were re-sealed. */
    public static final String CHAIN_CONFLICTS_TOTAL = "pdei_audit_chain_conflicts_total";

    /** Counter, tags: result (intact|broken). */
    public static final String CHAIN_VERIFICATIONS_TOTAL = "pdei_audit_chain_verifications_total";

    /** Timer. Wall time of one chain verification. */
    public static final String CHAIN_VERIFICATION_SECONDS = "pdei_audit_chain_verification_seconds";

    /** Counter. Entries rejected as unstorable and dead-lettered. */
    public static final String ENTRIES_REJECTED_TOTAL = "pdei_audit_entries_rejected_total";

    /** Gauge. Entries emitted by the most recent NDJSON export. */
    public static final String LAST_EXPORT_SIZE = "pdei_audit_last_export_size";

    /** Gauge. Merchant chains known to be broken as of the last verification sweep. */
    public static final String BROKEN_CHAINS = "pdei_audit_broken_chains";

    private final MeterRegistry registry;
    private final AtomicLong lastExportSize = new AtomicLong();
    private final AtomicInteger brokenChains = new AtomicInteger();

    public AuditMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            registry.gauge(LAST_EXPORT_SIZE, lastExportSize, AtomicLong::doubleValue);
            registry.gauge(BROKEN_CHAINS, brokenChains, AtomicInteger::doubleValue);
        }
    }

    /**
     * @param preserved true when the producer's own seal was kept, false when the entry was
     *                  re-sealed against this chain's head
     */
    public void appended(String entityType, boolean preserved) {
        counter(ENTRIES_APPENDED_TOTAL,
                MetricNames.Tag.TYPE, safe(entityType),
                "sealed", preserved ? "producer" : "resealed");
    }

    public void chainConflict() {
        counter(CHAIN_CONFLICTS_TOTAL, MetricNames.Tag.SERVICE, SERVICE);
    }

    public void rejected(String reason) {
        counter(ENTRIES_REJECTED_TOTAL, "reason", safe(reason));
    }

    public void chainVerified(boolean intact, long nanos) {
        counter(CHAIN_VERIFICATIONS_TOTAL, "result", intact ? "intact" : "broken");
        if (registry != null) {
            Timer.builder(CHAIN_VERIFICATION_SECONDS)
                    .register(registry)
                    .record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /** {@code pdei_events_processed_total{service,type,outcome}}. */
    public void eventProcessed(String eventType, String outcome) {
        counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, SERVICE,
                MetricNames.Tag.TYPE, safe(eventType),
                MetricNames.Tag.OUTCOME, safe(outcome));
    }

    /** {@code pdei_events_duplicate_total{service}}. */
    public void duplicateSuppressed() {
        counter(MetricNames.EVENTS_DUPLICATE_TOTAL, MetricNames.Tag.SERVICE, SERVICE);
    }

    /** {@code pdei_event_processing_latency_seconds{service,type}}. */
    public void eventLatency(String eventType, long nanos) {
        if (registry == null) {
            return;
        }
        Timer.builder(MetricNames.EVENT_PROCESSING_LATENCY_SECONDS)
                .tag(MetricNames.Tag.SERVICE, SERVICE)
                .tag(MetricNames.Tag.TYPE, safe(eventType))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void exported(long entries) {
        lastExportSize.set(entries);
    }

    public void brokenChains(int count) {
        brokenChains.set(count);
    }

    private void counter(String name, String... tags) {
        if (registry == null) {
            return;
        }
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
