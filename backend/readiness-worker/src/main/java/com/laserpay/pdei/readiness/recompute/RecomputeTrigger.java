package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.common.event.EventType;

/**
 * Why a readiness recomputation was requested.
 *
 * <p>These names are <strong>not</strong> free text: they are the exact vocabulary of the
 * {@code ck_readiness_snapshots_trigger} check constraint in {@code V6__readiness.sql}, and they
 * are written to {@code readiness_snapshots.trigger_reason}. Adding a member here without adding it
 * to the migration will fail the insert.
 *
 * <p>The ordering matters: when several events for one transaction collapse into a single
 * computation, the surviving trigger is the one with the highest {@link #precedence()}, so a
 * snapshot records the most specific reason it was recomputed rather than whichever event happened
 * to arrive last.
 */
public enum RecomputeTrigger {

    /** Any EVIDENCE event on {@code pdei.evidence.events.v1} (contract section 7). */
    EVIDENCE_EVENT(40),

    /** A state change on a linked entity: payment, order, shipment, delivery, refund, communication. */
    ENTITY_STATE_CHANGE(20),

    /** A new immutable policy version changed the requirement matrix. */
    POLICY_VERSION_CHANGE(60),

    /** The scheduled expiry sweep transitioned evidence out of ACTIVE. */
    NIGHTLY_SWEEP(50),

    /** {@code POST /transactions/{transactionId}/readiness/recompute} or an operator action. */
    MANUAL_RECOMPUTE(80),

    /** A dispute was raised or updated, so readiness must be scored against its reason code. */
    DISPUTE_EVENT(70);

    private final int precedence;

    RecomputeTrigger(int precedence) {
        this.precedence = precedence;
    }

    /** Higher wins when several triggers collapse into one computation. */
    public int precedence() {
        return precedence;
    }

    public RecomputeTrigger merge(RecomputeTrigger other) {
        if (other == null) {
            return this;
        }
        return other.precedence > this.precedence ? other : this;
    }

    /**
     * Classify an inbound canonical event.
     *
     * <p>Evidence and dispute events are explicit; everything else that can move readiness is an
     * entity state change. Readiness, case and audit events are our own output or someone else's
     * bookkeeping and never trigger a recomputation - that would be a feedback loop.
     */
    public static RecomputeTrigger forEvent(EventType eventType) {
        if (eventType == null) {
            return ENTITY_STATE_CHANGE;
        }
        if (eventType.isEvidenceEvent()) {
            return EVIDENCE_EVENT;
        }
        if (eventType.isDisputeEvent()) {
            return DISPUTE_EVENT;
        }
        return ENTITY_STATE_CHANGE;
    }
}
