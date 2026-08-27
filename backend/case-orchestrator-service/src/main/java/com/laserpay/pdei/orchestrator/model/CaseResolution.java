package com.laserpay.pdei.orchestrator.model;

/**
 * How a dispute case ended. Recorded on the {@code CaseClosed} event and on the
 * {@code dispute_cases} row so the funnel can distinguish "we submitted and won" from
 * "a human refused to submit" from "we ran out of time".
 */
public enum CaseResolution {

    /** Representment prepared, submitted, and the network outcome arrived. */
    SUBMITTED_AND_RESOLVED,
    /** Representment submitted; follow-up window elapsed without a network outcome. */
    SUBMITTED_AWAITING_OUTCOME,
    /** A human reviewed the proposal and declined to submit. */
    REJECTED_BY_HUMAN,
    /** Deterministic or AI assessment recommended accepting liability, and a human agreed. */
    LIABILITY_ACCEPTED,
    /** Evidence never became sufficient inside the 7-day wait and the assessment loop was exhausted. */
    EVIDENCE_INSUFFICIENT,
    /** Nobody acted on the escalation before the representment deadline. */
    ESCALATION_EXPIRED,
    /** The representment deadline passed before a package could be submitted. */
    DEADLINE_EXPIRED,
    /** {@code cancelCase} signal received. */
    CANCELLED,
    /** An activity failed non-retryably; compensation ran and the case was marked FAILED. */
    FAILED;

    public boolean isSuccessfulSubmission() {
        return this == SUBMITTED_AND_RESOLVED || this == SUBMITTED_AWAITING_OUTCOME;
    }
}
