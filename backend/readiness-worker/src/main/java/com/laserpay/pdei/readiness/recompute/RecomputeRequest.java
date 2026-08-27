package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.event.CanonicalEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * One request to recompute the readiness of a transaction.
 *
 * <p>Immutable and mergeable. The debouncer folds every request that arrives for the same
 * transaction inside the debounce window into a single instance via {@link #mergedWith}, so a burst
 * of twenty events produces one computation whose recorded trigger is the most specific of the
 * twenty.
 *
 * @param transactionId  the transaction to score; never null
 * @param merchantId     owning merchant, may be null when the event did not carry it (the engine
 *                       resolves it from the transaction row)
 * @param reasonCode     score against this dispute reason, or null for the merchant baseline
 *                       profile (PLATFORM-CONTRACT section 7)
 * @param trigger        why this recomputation was asked for; written to
 *                       {@code readiness_snapshots.trigger_reason}
 * @param triggerEventId the {@code eventId} that caused it; written to
 *                       {@code readiness_snapshots.trigger_event_id}
 * @param correlationId  propagated onto the emitted readiness events
 * @param requestedAt    when the request was first raised, used for the debounce ceiling
 */
public record RecomputeRequest(
        String transactionId,
        String merchantId,
        DisputeReasonCode reasonCode,
        RecomputeTrigger trigger,
        String triggerEventId,
        String correlationId,
        Instant requestedAt) {

    public RecomputeRequest {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        trigger = trigger == null ? RecomputeTrigger.ENTITY_STATE_CHANGE : trigger;
    }

    /** Request derived from an inbound canonical event. */
    public static RecomputeRequest fromEvent(String transactionId, CanonicalEvent event, Instant at) {
        return new RecomputeRequest(
                transactionId,
                event == null ? null : event.merchantId(),
                null,
                event == null ? RecomputeTrigger.ENTITY_STATE_CHANGE
                        : RecomputeTrigger.forEvent(event.eventType()),
                event == null ? null : event.eventId(),
                event == null ? null : event.correlationId(),
                at);
    }

    /** Request raised by the scheduled expiry sweep. */
    public static RecomputeRequest fromSweep(String transactionId, String merchantId,
                                             String triggerEventId, Instant at) {
        return new RecomputeRequest(transactionId, merchantId, null, RecomputeTrigger.NIGHTLY_SWEEP,
                triggerEventId, null, at);
    }

    /** Request raised by the at-risk scanner refreshing a stale snapshot. */
    public static RecomputeRequest stale(String transactionId, String merchantId, Instant at) {
        return new RecomputeRequest(transactionId, merchantId, null, RecomputeTrigger.ENTITY_STATE_CHANGE,
                null, null, at);
    }

    /**
     * Fold another request for the same transaction into this one.
     *
     * <p>Keeps the earliest {@code requestedAt} (so the maximum-delay ceiling measures from the
     * first event of the burst, not the last), the winning trigger, and the first non-null value of
     * everything else - later events must not erase context the earlier ones supplied.
     */
    public RecomputeRequest mergedWith(RecomputeRequest other) {
        if (other == null) {
            return this;
        }
        RecomputeTrigger winner = trigger.merge(other.trigger());
        boolean otherWins = winner != trigger;
        String mergedEventId = triggerEventId;
        if ((otherWins || mergedEventId == null) && other.triggerEventId() != null) {
            mergedEventId = other.triggerEventId();
        }
        return new RecomputeRequest(
                transactionId,
                merchantId != null ? merchantId : other.merchantId(),
                reasonCode != null ? reasonCode : other.reasonCode(),
                winner,
                mergedEventId,
                correlationId != null ? correlationId : other.correlationId(),
                earliest(requestedAt, other.requestedAt()));
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
