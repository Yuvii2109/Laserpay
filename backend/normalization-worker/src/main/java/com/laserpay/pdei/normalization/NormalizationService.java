package com.laserpay.pdei.normalization;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.normalization.adapter.SourceAdapter;
import com.laserpay.pdei.normalization.adapter.SourceAdapterRegistry;
import com.laserpay.pdei.normalization.adapter.UnmappableEventException;
import com.laserpay.pdei.normalization.config.NormalizationProperties;
import com.laserpay.pdei.normalization.observability.KafkaTracing;
import com.laserpay.pdei.normalization.support.IdempotencyGuard;
import com.laserpay.pdei.normalization.upcast.UpcasterChain;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Turns one raw source event into one canonical event.
 *
 * <p>The whole worker is this pipeline:
 *
 * <pre>
 *   raw record -&gt; idempotency claim -&gt; upcaster chain -&gt; adapter lookup -&gt; adapter.normalize
 *              -&gt; publish to pdei.canonical.events.v1
 * </pre>
 *
 * <p>Three properties are worth stating explicitly because everything downstream relies on them.
 *
 * <p><strong>Normalization is a pure function of the raw record.</strong> The canonical
 * {@code eventId} is derived deterministically from {@code rawEventId} and the resolved event type,
 * so replaying the raw topic re-emits identical ids and every downstream consumer's dedupe collapses
 * the repeat. Replay is a first-class operation here, not an accident to be survived.
 *
 * <p><strong>Lateness is preserved, never collapsed.</strong> {@code occurredAt} comes from the
 * source payload; {@code observedAt} is stamped here, once, from the injected clock. A delivery
 * event that took six hours to reach us stays six hours late all the way to the timeline, which is
 * what makes the out-of-order handling in state-builder-worker testable rather than theoretical.
 *
 * <p><strong>Publication is synchronous and inside the transaction.</strong> The idempotency claim
 * and the publication succeed or fail together: if the broker rejects the send, the claim rolls back
 * and redelivery re-normalizes. The alternative (fire-and-forget) can commit a claim for an event
 * that never reached the canonical topic, which is an event silently lost.
 */
public class NormalizationService {

    private static final Logger log = LoggerFactory.getLogger(NormalizationService.class);
    private static final String SERVICE = "normalization-worker";

    private final SourceAdapterRegistry registry;
    private final UpcasterChain upcasterChain;
    private final IdempotencyGuard idempotency;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clocks clock;
    private final MeterRegistry meterRegistry;
    private final Duration publishTimeout;
    private final Duration latenessWarnThreshold;

    public NormalizationService(SourceAdapterRegistry registry,
                                UpcasterChain upcasterChain,
                                IdempotencyGuard idempotency,
                                KafkaTemplate<String, Object> kafkaTemplate,
                                Clocks clock,
                                MeterRegistry meterRegistry,
                                NormalizationProperties properties) {
        this.registry = registry;
        this.upcasterChain = upcasterChain;
        this.idempotency = idempotency;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.publishTimeout = properties.getPublishTimeout();
        this.latenessWarnThreshold = properties.getLatenessWarnThreshold();
    }

    /**
     * Normalizes and publishes one raw envelope.
     *
     * @return the published canonical event, or {@code null} when the envelope was a duplicate
     * @throws UnmappableEventException when no adapter can translate it - the caller dead-letters
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CanonicalEvent normalizeAndPublish(RawEventEnvelope raw) {
        long startNanos = System.nanoTime();

        // The raw event id IS the event id on this topic: RawEventEnvelope has no separate one, and
        // ingestion guarantees it is stable for a given source fact.
        if (!idempotency.claim(raw.rawEventId())) {
            duplicate();
            log.debug("skipping duplicate rawEventId={} sourceSystem={}", raw.rawEventId(),
                    raw.sourceSystem());
            return null;
        }

        RawEventEnvelope migrated = upcasterChain.upcastAndStamp(raw);
        SourceAdapter adapter = registry.require(migrated);

        Instant observedAt = clock.now();
        CanonicalEvent event = adapter.normalize(migrated, observedAt);
        warnIfVeryLate(event);

        publish(event);
        idempotency.confirm(raw.rawEventId());

        record(event, startNanos);
        log.info("normalized {} {} from {} rawEventId={} lagMs={}", event.eventType(),
                event.aggregateId(), adapter.sourceSystem(), raw.rawEventId(),
                event.ingestionLagMillis());
        return event;
    }

    // --- publication ----------------------------------------------------------------------------

    private void publish(CanonicalEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(
                Topics.CANONICAL_EVENTS, null, event.partitionKey(), event);
        header(record, EventHeaders.EVENT_ID, event.eventId());
        header(record, EventHeaders.EVENT_TYPE, event.eventType().name());
        header(record, EventHeaders.MERCHANT_ID, event.merchantId());
        header(record, EventHeaders.CORRELATION_ID, event.correlationId());
        header(record, EventHeaders.SCHEMA_VERSION, Integer.toString(event.schemaVersion()));
        KafkaTracing.inject(record.headers());

        try {
            kafkaTemplate.send(record).get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing " + event.eventId(), e);
        } catch (Exception e) {
            // Rolls back the idempotency claim with the transaction: the event will be retried.
            throw new IllegalStateException("failed to publish " + event.eventType() + " "
                    + event.eventId() + " to " + Topics.CANONICAL_EVENTS, e);
        }
    }

    private static void header(ProducerRecord<String, Object> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- observability --------------------------------------------------------------------------

    /**
     * Lateness is normal and expected; extreme lateness usually means a source system replayed its
     * own history, which is worth a log line but never a failure.
     */
    private void warnIfVeryLate(CanonicalEvent event) {
        long lagMillis = event.ingestionLagMillis();
        if (latenessWarnThreshold != null && lagMillis > latenessWarnThreshold.toMillis()) {
            log.warn("late event {} {} occurredAt={} observedAt={} lagMs={}", event.eventType(),
                    event.aggregateId(), event.occurredAt(), event.observedAt(), lagMillis);
        }
    }

    private void record(CanonicalEvent event, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                    MetricNames.Tag.SERVICE, SERVICE,
                    MetricNames.Tag.TYPE, event.eventType().name(),
                    MetricNames.Tag.OUTCOME, MetricNames.Outcome.SUCCESS).increment();
            Timer.builder(MetricNames.EVENT_PROCESSING_LATENCY_SECONDS)
                    .tag(MetricNames.Tag.SERVICE, SERVICE)
                    .tag(MetricNames.Tag.TYPE, event.eventType().name())
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.debug("failed to record normalization metrics: {}", e.toString());
        }
    }

    private void duplicate() {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL,
                    MetricNames.Tag.SERVICE, SERVICE).increment();
            meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                    MetricNames.Tag.SERVICE, SERVICE,
                    MetricNames.Tag.TYPE, "unknown",
                    MetricNames.Tag.OUTCOME, MetricNames.Outcome.DUPLICATE).increment();
        } catch (RuntimeException e) {
            log.debug("failed to record duplicate metric: {}", e.toString());
        }
    }

    /** The consumer group this service dedupes under; exposed for diagnostics. */
    public String consumerGroup() {
        return ConsumerGroups.PDEI_NORMALIZATION_WORKER;
    }
}
