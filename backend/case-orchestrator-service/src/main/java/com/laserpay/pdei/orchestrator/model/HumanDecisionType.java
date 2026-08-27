package com.laserpay.pdei.orchestrator.model;

/**
 * What a reviewer decided at step 8. Mapped from the api-gateway routes
 * {@code POST /api/v1/cases/{caseId}/approve|reject|submit}.
 */
public enum HumanDecisionType {

    /** Proceed to package assembly and submission. */
    APPROVE,
    /** Do not submit; close the case and accept the chargeback. */
    REJECT,
    /** Explicit submit instruction: approve and skip any remaining discretionary waits. */
    SUBMIT,
    /** Send the case back to evidence gathering for another assessment round. */
    REQUEST_MORE_EVIDENCE;

    /** True when the decision lets the workflow move on to step 9. */
    public boolean isApproval() {
        return this == APPROVE || this == SUBMIT;
    }
}
