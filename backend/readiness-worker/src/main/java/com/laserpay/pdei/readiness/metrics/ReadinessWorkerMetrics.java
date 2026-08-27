package com.laserpay.pdei.readiness.metrics;

import com.laserpay.pdei.common.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer instrumentation for the readiness worker, using the names in PLATFORM-CONTRACT
 * section 13 verbatim through {@link MetricNames}.
 *
 * <p>Division of labour with the domain engine:
 * {@code pdei_readiness_computation_seconds} and {@code pdei_readiness_score{merchant}} are recorded
 * by {@code evidence-core}'s {@code ReadinessEngine} itself (it is the thing being timed, and the
 * score is its output). {@code ReadinessWorkerConfig} therefore always constructs the engine with
 * this application's {@link MeterRegistry}, otherwise those two contract metrics would silently not
 * exist. Everything the <em>process</em> does - consumption, dedupe, debounce, sweep - is counted
 * here.
 *
 * <p>Tag cardinality is deliberately bounded: event type names and outcome names only. No
 * transaction id, no evidence id, no correlation id ever becomes a tag.
 */
public class ReadinessWorkerMetrics {

    /** Value of the {@code service} tag on every counter this class registers. */
    public static final String SERVICE = "readiness-worker";

    /** Recomputations that a burst collapsed away. Not a contract metric; the debounce evidence. */
    public static final String RECOMPUTE_COALESCED_TOTAL = "pdei_readiness_recompute_coalesced_total";

    /** Recomputations skipped because another worker held {@code pdei:lock:readiness:{txId}}. */
    public static final String RECOMPUTE_LOCK_CONTENDED_TOTAL = "pdei_readiness_recompute_lock_contended_total";

    /** Evidence lifecycle transitions performed by the sweep, tagged by target status. */
    public static final String EXPIRY_TRANSITIONS_TOTAL = "pdei_readiness_expiry_transitions_total";

    /** Size of the materialised at-risk feed after the most recent scan. */
    public static final String AT_RISK_FEED_SIZE = "pdei_readiness_at_risk_feed_size";

    private final MeterRegistry registry;
    private final AtomicInteger atRiskFeedSize = new AtomicInteger();
    private final AtomicInteger pendingRecomputes = new AtomicInteger();

    public ReadinessWorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            registry.gauge(AT_RISK_FEED_SIZE, atRiskFeedSize, AtomicInteger::doubleValue);
            registry.gauge("pdei_readiness_pending_recomputes", pendingRecomputes,
                    AtomicInteger::doubleValue);
        }
    }

    /** {@code pdei_events_processed_total{service,type,outcome}}. */
    public void eventProcessed(String eventType, String outcome) {
        counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, SERVICE,
                MetricNames.Tag.TYPE, safe(eventType),
                MetricNames.Tag.OUTCOME, safe(outcome));
    }

    /** {@code pdei_events_duplicate_total{service}} - idempotency suppressed a redelivery. */
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

    public void recomputeCoalesced() {
        counter(RECOMPUTE_COALESCED_TOTAL, MetricNames.Tag.SERVICE, SERVICE);
    }

    public void recomputeLockContended() {
        counter(RECOMPUTE_LOCK_CONTENDED_TOTAL, MetricNames.Tag.SERVICE, SERVICE);
    }

    public void expiryTransition(String toStatus) {
        counter(EXPIRY_TRANSITIONS_TOTAL, MetricNames.Tag.STATUS, safe(toStatus));
    }

    public void atRiskFeedSize(int size) {
        atRiskFeedSize.set(size);
    }

    public void pendingRecomputes(int pending) {
        pendingRecomputes.set(pending);
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
