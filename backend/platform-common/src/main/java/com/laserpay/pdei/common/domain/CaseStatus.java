package com.laserpay.pdei.common.domain;

/**
 * Dispute-case (workflow) status (PLATFORM-CONTRACT section 6).
 *
 * <p>Mirrors the {@code DisputeCaseWorkflow} stages in PLATFORM-CONTRACT section 10 and drives the
 * swimlanes on the {@code /cases} screen. {@link #AWAITING_APPROVAL} is the human gate: nothing
 * leaves that state without an explicit operator signal.
 */
public enum CaseStatus {
    CREATED,
    ASSEMBLING,
    INVESTIGATING,
    AWAITING_EVIDENCE,
    AWAITING_APPROVAL,
    PREPARED,
    SUBMITTED,
    CLOSED,
    FAILED;

    public boolean isTerminal() {
        return this == CLOSED || this == FAILED;
    }

    /** Statuses where the workflow is parked on an external signal or timer. */
    public boolean isWaiting() {
        return this == AWAITING_EVIDENCE || this == AWAITING_APPROVAL;
    }
}
