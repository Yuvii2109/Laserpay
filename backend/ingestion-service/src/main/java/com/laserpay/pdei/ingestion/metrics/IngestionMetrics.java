package com.laserpay.pdei.ingestion.metrics;

import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.model.IngestionStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The ingestion half of the platform's metric contract (PLATFORM-CONTRACT section 13).
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code pdei_events_ingested_total{source,type}} - accepted and published;</li>
 *   <li>{@code pdei_events_duplicate_total{service}} - suppressed by idempotency;</li>
 *   <li>{@code pdei_events_processed_total{service,type,outcome}} - the full accounting, including
 *       {@code failure} for a validation rejection and {@code dead_lettered} for a publish failure
 *       that reached the DLQ;</li>
 *   <li>{@code pdei_event_processing_latency_seconds{service,type}} - per-event wall time from
 *       validation to broker acknowledgement.</li>
 * </ul>
 *
 * <p><strong>Cardinality discipline.</strong> The {@code type} tag is the <em>resolved schema
 * name</em>, not the free-text source event type: a misconfigured adapter emitting a random string
 * per event would otherwise create a time series per event and take the Prometheus instance with
 * it. Unrecognised types collapse to {@value #UNMAPPED_TYPE}, which is itself a useful signal - a
 * rising {@code type="unmapped"} series means an adapter needs a schema.
 *
 * <p>The plain {@link AtomicLong} totals alongside the meters exist for
 * {@code GET /ingest/v1/stats}, which must answer without reaching into the meter registry's
 * tagged children.
 */
@Component
public class IngestionMetrics {

    /** Service tag value, matching the module name used everywhere else in the platform. */
    public static final String SERVICE = "ingestion-service";

    /** {@code type} tag value for an event with no registered schema. */
    public static final String UNMAPPED_TYPE = "unmapped";

    /**
     * A {@code source} tag value must look like an adapter identity, not like data. Anything that
     * does not match folds to {@value #OTHER_SOURCE}, which caps the series count no matter what a
     * misbehaving client sends.
     */
    private static final Pattern SAFE_SOURCE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");

    /** {@code source} tag value for a source system whose name is not tag-safe. */
    public static final String OTHER_SOURCE = "other";

    private final MeterRegistry registry;
    private final Clocks clock;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final java.time.Instant since;

    public IngestionMetrics(MeterRegistry registry, Clocks clock) {
        this.registry = registry;
        this.clock = clock;
        this.since = clock.now();
        // Register the counters eagerly so a freshly started service scrapes as 0 rather than absent;
        // "no data" and "nothing happened" are different answers and dashboards must not conflate them.
        registry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL, MetricNames.Tag.SERVICE, SERVICE);
    }

    /** One event validated, claimed and acknowledged by the broker. */
    public void recordAccepted(String sourceSystem, String type) {
        accepted.incrementAndGet();
        registry.counter(MetricNames.EVENTS_INGESTED_TOTAL,
                MetricNames.Tag.SOURCE, source(sourceSystem),
                MetricNames.Tag.TYPE, type(type)).increment();
        processed(type, MetricNames.Outcome.SUCCESS);
    }

    /** One event suppressed because its idempotency key was already claimed. */
    public void recordDuplicate(String sourceSystem, String type) {
        duplicates.incrementAndGet();
        registry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL, MetricNames.Tag.SERVICE, SERVICE).increment();
        processed(type, MetricNames.Outcome.DUPLICATE);
    }

    /** One event refused: schema violation, unknown schema, or malformed submission. */
    public void recordRejected(String sourceSystem, String type) {
        rejected.incrementAndGet();
        processed(type, MetricNames.Outcome.FAILURE);
    }

    /**
     * One event whose publication failed and which was written to {@code pdei.dlq.v1}.
     *
     * <p>Also counts towards {@code rejected}, because such an event appears in the response's
     * {@code rejected[]} array; {@code deadLettered} is the informational subset of it. Exactly one
     * {@code pdei_events_processed_total} increment is emitted per event either way, so the
     * Prometheus outcome split still sums to the total processed.
     */
    public void recordDeadLettered(String sourceSystem, String type) {
        deadLettered.incrementAndGet();
        rejected.incrementAndGet();
        processed(type, MetricNames.Outcome.DEAD_LETTERED);
    }

    /** Wall time for one event, validation through broker acknowledgement. */
    public void recordLatency(String type, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        Timer.builder(MetricNames.EVENT_PROCESSING_LATENCY_SECONDS)
                .tag(MetricNames.Tag.SERVICE, SERVICE)
                .tag(MetricNames.Tag.TYPE, type(type))
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** Snapshot for {@code GET /ingest/v1/stats}. */
    public IngestionStats snapshot(int registeredSchemas) {
        return new IngestionStats(accepted.get(), rejected.get(), duplicates.get(), deadLettered.get(),
                registeredSchemas, since, clock.now());
    }

    private void processed(String type, String outcome) {
        registry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, SERVICE,
                MetricNames.Tag.TYPE, type(type),
                MetricNames.Tag.OUTCOME, outcome).increment();
    }

    /**
     * Bounds the {@code type} tag. Callers pass the resolved schema name (bounded by the schema
     * registry) or null.
     */
    static String type(String type) {
        return type == null || type.isBlank() ? UNMAPPED_TYPE : type;
    }

    /**
     * Bounds the {@code source} tag. Source systems are adapter identities - a handful of stable
     * names configured per deployment - so they are passed through when they look like one, and
     * folded to {@value #OTHER_SOURCE} when they do not.
     */
    static String source(String sourceSystem) {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            return OTHER_SOURCE;
        }
        String normalized = sourceSystem.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return SAFE_SOURCE.matcher(normalized).matches() ? normalized : OTHER_SOURCE;
    }
}
