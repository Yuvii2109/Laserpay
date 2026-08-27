package com.laserpay.pdei.orchestrator.model;

import java.time.Instant;

/**
 * Payload of the {@code humanDecision} signal - the only way a case leaves step 8.
 *
 * <p>Produced by {@code POST /api/v1/cases/{caseId}/approve|reject|submit} on the api-gateway,
 * relayed through {@code POST /orchestrator/v1/cases/{caseId}/signal}.</p>
 *
 * @param decision what the reviewer chose
 * @param actor    the human's identity; recorded on the case row and in the audit chain
 * @param notes    free text shown on the Case X-Ray gate tab
 * @param decidedAt when the human acted, for the audit trail. The workflow uses its own clock for
 *                  anything time-dependent - this field is evidence, not control flow.
 */
public record HumanDecision(
        HumanDecisionType decision,
        String actor,
        String notes,
        Instant decidedAt) {

    public static HumanDecision approve(String actor, String notes) {
        return new HumanDecision(HumanDecisionType.APPROVE, actor, notes, null);
    }

    public static HumanDecision reject(String actor, String notes) {
        return new HumanDecision(HumanDecisionType.REJECT, actor, notes, null);
    }

    public static HumanDecision submit(String actor, String notes) {
        return new HumanDecision(HumanDecisionType.SUBMIT, actor, notes, null);
    }

    public static HumanDecision requestMoreEvidence(String actor, String notes) {
        return new HumanDecision(HumanDecisionType.REQUEST_MORE_EVIDENCE, actor, notes, null);
    }

    public boolean isApproval() {
        return decision != null && decision.isApproval();
    }
}
