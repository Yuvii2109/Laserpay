package com.laserpay.pdei.readiness.consume;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import com.laserpay.pdei.readiness.persistence.TransactionResolver;
import com.laserpay.pdei.readiness.recompute.ReadinessCache;
import com.laserpay.pdei.readiness.recompute.RecomputeDebouncer;
import com.laserpay.pdei.readiness.recompute.RecomputeRequest;
import com.laserpay.pdei.readiness.recompute.RecomputeTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * The logic both Kafka consumers share: dedupe, resolve the affected transaction, schedule a
 * debounced recomputation.
 *
 * <p>Kept out of the {@code @KafkaListener} classes so the decision-making is testable without a
 * broker, and so the two topics cannot drift apart in how they treat duplicates.
 *
 * <p><strong>Idempotency comes first.</strong> Every consumer in PDEI claims
 * {@code (eventId, consumerGroup)} before doing anything (PLATFORM-CONTRACT section 4). Here the
 * claim is cheap to honour, because the work it guards - scheduling a recomputation - is itself
 * idempotent: the deterministic engine reading the same rows produces the same score. Duplicates
 * are suppressed to save work and to keep {@code pdei_events_duplicate_total} meaningful, not
 * because a duplicate would corrupt anything.
 *
 * <p><strong>Our own output is ignored.</strong> READINESS events published by this worker are not
 * a reason to recompute; consuming them would be an infinite loop.
 */
public class EventIntake {

    private static final Logger log = LoggerFactory.getLogger(EventIntake.class);

    private final IdempotencyGuard idempotency;
    private final TransactionResolver resolver;
    private final RecomputeDebouncer debouncer;
    private final ReadinessCache cache;
    private final ReadinessWorkerMetrics metrics;
    private final Clocks clock;

    public EventIntake(IdempotencyGuard idempotency, TransactionResolver resolver,
                       RecomputeDebouncer debouncer, ReadinessCache cache,
                       ReadinessWorkerMetrics metrics, Clocks clock) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.debouncer = Objects.requireNonNull(debouncer, "debouncer must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.metrics = metrics;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Handle one canonical event.
     *
     * @return the outcome, which is also the value of the {@code outcome} tag on
     *     {@code pdei_events_processed_total}
     */
    public Outcome accept(CanonicalEvent event) {
        if (event == null) {
            return Outcome.SKIPPED;
        }
        long startNanos = System.nanoTime();
        String typeName = event.eventType() == null ? null : event.eventType().name();

        try {
            if (!isRelevant(event.eventType())) {
                record(typeName, MetricNames.Outcome.SKIPPED, startNanos);
                return Outcome.SKIPPED;
            }
            if (!idempotency.claim(event.eventId())) {
                record(typeName, MetricNames.Outcome.DUPLICATE, startNanos);
                return Outcome.DUPLICATE;
            }

            Optional<String> transactionId = resolver.resolve(event);
            if (transactionId.isEmpty()) {
                // The aggregate is not linked to a transaction yet. Nothing to score.
                log.debug("no transaction resolved for event {} ({} {})",
                        event.eventId(), event.aggregateType(), event.aggregateId());
                record(typeName, MetricNames.Outcome.SKIPPED, startNanos);
                return Outcome.UNRESOLVED;
            }

            String id = transactionId.get();
            DisputeReasonCode reasonCode = resolver.reasonCode(event).orElse(null);

            RecomputeRequest request = new RecomputeRequest(
                    id,
                    event.merchantId(),
                    reasonCode,
                    RecomputeTrigger.forEvent(event.eventType()),
                    event.eventId(),
                    event.correlationId(),
                    clock.now());

            // The cached snapshot is now known to be stale. Evicting before the debounce window
            // closes means a reader gets a miss and re-reads the database instead of being served a
            // score that we already know is out of date.
            cache.evict(id);

            boolean opened = debouncer.submit(request);
            record(typeName, MetricNames.Outcome.SUCCESS, startNanos);
            return opened ? Outcome.SCHEDULED : Outcome.COALESCED;
        } catch (RuntimeException e) {
            record(typeName, MetricNames.Outcome.FAILURE, startNanos);
            throw e;
        }
    }

    /**
     * Which event types can move a readiness score.
     *
     * <p>EVIDENCE and DISPUTE events obviously can. Entity state changes can, because a delivery or
     * a refund changes what the evidence has to prove. READINESS, CASE and AUDIT events cannot:
     * they are downstream of the score, and reacting to them would be a feedback loop.
     */
    static boolean isRelevant(EventType eventType) {
        if (eventType == null) {
            return false;
        }
        return !eventType.isReadinessEvent() && !eventType.isCaseEvent() && !eventType.isAuditEvent();
    }

    private void record(String eventType, String outcome, long startNanos) {
        if (metrics == null) {
            return;
        }
        metrics.eventProcessed(eventType, outcome);
        metrics.eventLatency(eventType, System.nanoTime() - startNanos);
    }

    /** What the intake did with an event. */
    public enum Outcome {
        /** A new debounce window was opened; a computation will follow. */
        SCHEDULED,
        /** Merged into an open debounce window; no extra computation. */
        COALESCED,
        /** Already handled by this consumer group. */
        DUPLICATE,
        /** Not a readiness-relevant event type. */
        SKIPPED,
        /** Relevant, but no transaction could be resolved yet. */
        UNRESOLVED
    }
}
