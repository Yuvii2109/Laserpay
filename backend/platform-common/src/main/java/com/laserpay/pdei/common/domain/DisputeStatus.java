package com.laserpay.pdei.common.domain;

/**
 * Dispute lifecycle (PLATFORM-CONTRACT section 6).
 *
 * <p>Tracks the dispute itself. The orchestration of work against it is tracked separately by
 * {@link CaseStatus}: one dispute has one case, but the two move at different rhythms.
 */
public enum DisputeStatus {
    OPEN,
    EVIDENCE_GATHERING,
    UNDER_INVESTIGATION,
    AWAITING_HUMAN_REVIEW,
    REPRESENTMENT_PREPARED,
    SUBMITTED,
    WON,
    LOST,
    EXPIRED,
    WITHDRAWN;

    /** No further transitions are possible from a terminal status. */
    public boolean isTerminal() {
        return this == WON || this == LOST || this == EXPIRED || this == WITHDRAWN;
    }

    /** Whether the outcome counts towards the merchant win-rate statistic. */
    public boolean isResolved() {
        return this == WON || this == LOST;
    }
}
