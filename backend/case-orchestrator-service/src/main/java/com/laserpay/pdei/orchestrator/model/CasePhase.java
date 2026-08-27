package com.laserpay.pdei.orchestrator.model;

/**
 * Every state {@code DisputeCaseWorkflow} can be in, and where it sits in the twelve steps of
 * PLATFORM-CONTRACT section 10.
 *
 * <p>This enum is the state machine. {@code getProgress()} projects it to a percentage for the
 * Case X-Ray screen, and {@code getCaseState()} reports it verbatim so an operator can see exactly
 * which step a case has been parked on for three days.</p>
 *
 * <p>{@code step} is the contract step number (1-12); 0 means "not inside a numbered step"
 * (created, cancelled, failed).</p>
 */
public enum CasePhase {

    /** Workflow accepted, nothing executed yet. */
    CREATED(0, "Workflow started"),
    /** Step 1 - {@code openCase}. */
    OPENING(1, "Opening case"),
    /** Step 2 - {@code gatherEvidence}. */
    GATHERING_EVIDENCE(2, "Gathering evidence"),
    /** Step 3 - {@code detectGaps}. */
    DETECTING_GAPS(3, "Detecting evidence gaps"),
    /** Step 4 - blocked on the missing-evidence timer / {@code evidenceArrived} signal. */
    AWAITING_EVIDENCE(4, "Awaiting missing evidence"),
    /** Step 5 - {@code runAdmissionControl}. */
    ADMISSION_CONTROL(5, "Running AI admission control"),
    /** Step 6 - {@code investigate} (AI or deterministic). */
    INVESTIGATING(6, "Investigating"),
    /** Step 7 - {@code validateAndGate}. */
    GATING(7, "Validating against the safety gate"),
    /** Step 8 - blocked on {@code humanDecision}. */
    AWAITING_APPROVAL(8, "Awaiting human approval"),
    /** Step 8 - approval timeout expired, escalated, still waiting. */
    ESCALATED(8, "Escalated after approval timeout"),
    /** Step 9 - {@code prepareRepresentmentPackage}. */
    PREPARING_PACKAGE(9, "Preparing representment package"),
    /** Step 10 - {@code submitRepresentment}. */
    SUBMITTING(10, "Submitting representment"),
    /** Step 11 - follow-up timer loop. */
    FOLLOW_UP(11, "Following up until the network responds"),
    /** Step 12 - {@code closeCase} in flight. */
    CLOSING(12, "Closing case"),
    /** Step 12 complete. */
    CLOSED(12, "Closed"),
    /** Terminal: the workflow gave up after compensation. */
    FAILED(0, "Failed"),
    /** Terminal: {@code cancelCase} signal honoured. */
    CANCELLED(0, "Cancelled");

    /** Total number of contract steps, used as the progress denominator. */
    public static final int TOTAL_STEPS = 12;

    private final int step;
    private final String description;

    CasePhase(int step, String description) {
        this.step = step;
        this.description = description;
    }

    public int step() {
        return step;
    }

    public String description() {
        return description;
    }

    /** Progress in whole percent. Terminal phases always report 100. */
    public int percent() {
        if (this == CLOSED || this == FAILED || this == CANCELLED) {
            return 100;
        }
        return Math.round((step * 100.0f) / TOTAL_STEPS);
    }

    /** True while the workflow is parked on a timer or a signal rather than doing work. */
    public boolean isWaiting() {
        return this == AWAITING_EVIDENCE || this == AWAITING_APPROVAL || this == ESCALATED
                || this == FOLLOW_UP;
    }

    public boolean isTerminal() {
        return this == CLOSED || this == FAILED || this == CANCELLED;
    }
}
