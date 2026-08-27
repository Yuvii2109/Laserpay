package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.statebuilder.support.IdempotencyGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * The transactional unit of work for one canonical event.
 *
 * <pre>
 *   canonical event -&gt; idempotency claim -&gt; dispatch to handler -&gt; projections + derived evidence
 *                                                                  + forwarded events
 * </pre>
 *
 * <h2>One transaction, three effects</h2>
 *
 * The idempotency claim, the projection writes and the forwarded Kafka records all commit together.
 * That is what makes "exactly once, as observed by the database" true here despite at-least-once
 * delivery:
 *
 * <ul>
 *   <li>a crash before commit leaves no claim, so redelivery re-processes;</li>
 *   <li>a crash after commit leaves a claim, so redelivery is suppressed;</li>
 *   <li>a broker failure while forwarding throws, rolling back the projection with the claim.</li>
 * </ul>
 *
 * <p>The MinIO write inside evidence derivation is the one effect outside the transaction. That is
 * deliberate and safe in this direction: {@code EvidenceService} writes the object <em>before</em>
 * the row, so a rollback leaves an orphaned object (harmless, reclaimable, and content-addressed so
 * a retry rewrites the identical bytes) rather than a row pointing at nothing.
 *
 * <h2>Two layers of idempotency</h2>
 *
 * The {@code processed_events} claim stops an event being handled twice. The per-row watermark in
 * {@link com.laserpay.pdei.statebuilder.projection.ProjectionWatermark} stops an event being
 * <em>applied</em> twice, and survives a {@code processed_events} prune or a deliberate replay. The
 * second is the one that matters for correctness; the first is what keeps replays cheap.
 */
public class StateBuilderService {

    private static final Logger log = LoggerFactory.getLogger(StateBuilderService.class);
    private static final String SERVICE = "state-builder-worker";

    private final StateBuilderDispatcher dispatcher;
    private final IdempotencyGuard idempotency;
    private final MeterRegistry meterRegistry;

    public StateBuilderService(StateBuilderDispatcher dispatcher,
                               IdempotencyGuard idempotency,
                               MeterRegistry meterRegistry) {
        this.dispatcher = dispatcher;
        this.idempotency = idempotency;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Handles one canonical event.
     *
     * @return {@code true} when the event was applied, {@code false} when it was a duplicate or a
     *         type this worker does not project
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean handle(CanonicalEvent event) {
        long startNanos = System.nanoTime();

        if (!idempotency.claim(event.eventId())) {
            count(MetricNames.Outcome.DUPLICATE, event);
            duplicate();
            log.debug("skipping duplicate {} {}", event.eventType(), event.eventId());
            return false;
        }

        boolean dispatched = dispatcher.dispatch(event);
        idempotency.confirm(event.eventId());

        if (!dispatched) {
            count(MetricNames.Outcome.SKIPPED, event);
            return false;
        }

        count(MetricNames.Outcome.SUCCESS, event);
        recordLatency(event, startNanos);
        log.info("applied {} {} to {} {} (occurredAt={} lagMs={})", event.eventType(),
                event.eventId(), event.aggregateType(), event.aggregateId(), event.occurredAt(),
                event.ingestionLagMillis());
        return true;
    }

    // --- metrics --------------------------------------------------------------------------------

    private void count(String outcome, CanonicalEvent event) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                    MetricNames.Tag.SERVICE, SERVICE,
                    MetricNames.Tag.TYPE, event.eventType().name(),
                    MetricNames.Tag.OUTCOME, outcome).increment();
        } catch (RuntimeException e) {
            log.debug("failed to record processing metric: {}", e.toString());
        }
    }

    private void duplicate() {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL,
                    MetricNames.Tag.SERVICE, SERVICE).increment();
        } catch (RuntimeException e) {
            log.debug("failed to record duplicate metric: {}", e.toString());
        }
    }

    private void recordLatency(CanonicalEvent event, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(MetricNames.EVENT_PROCESSING_LATENCY_SECONDS)
                    .tag(MetricNames.Tag.SERVICE, SERVICE)
                    .tag(MetricNames.Tag.TYPE, event.eventType().name())
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.debug("failed to record latency metric: {}", e.toString());
        }
    }

    /** The consumer group this service dedupes under; exposed for diagnostics. */
    public String consumerGroup() {
        return ConsumerGroups.PDEI_STATE_BUILDER_WORKER;
    }
}
