package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.RecommendedAction;

import java.util.List;

/** Result of evaluating one proposed action against a policy version. */
public record PolicyDecision(
        boolean permitted,
        RecommendedAction action,
        String policyVersionId,
        List<String> reasons) {

    public PolicyDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static PolicyDecision permit(RecommendedAction action, String policyVersionId) {
        return new PolicyDecision(true, action, policyVersionId, List.of());
    }

    public static PolicyDecision refuse(RecommendedAction action, String policyVersionId, List<String> reasons) {
        return new PolicyDecision(false, action, policyVersionId, reasons);
    }
}
