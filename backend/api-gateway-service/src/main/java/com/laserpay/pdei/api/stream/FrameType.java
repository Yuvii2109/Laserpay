package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.common.event.EventType;

/**
 * The closed set of WebSocket frame types from PLATFORM-CONTRACT.md section 8.1.
 *
 * <p>The contract fixes this union, so the canonical {@code EventType} enum, which has 28 members,
 * has to fold onto seven. The fold is lossy on purpose and the loss is recovered in the payload:
 * every frame's {@code data} carries the original {@code eventType} string, so a client that cares
 * about the difference between {@code EvidenceExpired} and {@code EvidenceInvalidated} can read it,
 * while a client that just wants to refresh the evidence panel switches on the frame type.</p>
 *
 * <p>Specifically: all three EVIDENCE events map to {@code EVIDENCE_ADDED}, all three DISPUTE events
 * to {@code DISPUTE_CREATED}, and all seven CASE events to {@code CASE_UPDATED}. Widening the union
 * would be a contract change, and quietly dropping the events that do not fit would leave the
 * control tower stale after an invalidation, which is exactly the moment it must not be.</p>
 */
public enum FrameType {

    READINESS_UPDATED,
    EVIDENCE_ADDED,
    DISPUTE_CREATED,
    CASE_UPDATED,
    GAP_DETECTED,

    /**
     * Emitted when the simulator injects chaos. No topic the gateway consumes carries this today;
     * the type exists because the contract declares it and {@code StreamHub.broadcast} accepts any
     * frame, so a future simulator feed needs no change here.
     */
    CHAOS_INJECTED,

    HEARTBEAT;

    /**
     * @return the frame a canonical event becomes, or null when the event is not one the control
     *         tower shows (nothing is broadcast for those)
     */
    public static FrameType forEvent(EventType eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case ReadinessRecomputed -> READINESS_UPDATED;
            case ReadinessGapDetected -> GAP_DETECTED;
            case EvidenceAdded, EvidenceExpired, EvidenceInvalidated -> EVIDENCE_ADDED;
            case DisputeCreated, DisputeUpdated, DisputeClosed -> DISPUTE_CREATED;
            case CaseOpened, CaseEvidenceAttached, CaseInvestigated, CasePrepared,
                 CaseEscalated, CaseSubmitted, CaseClosed -> CASE_UPDATED;
            default -> null;
        };
    }
}
