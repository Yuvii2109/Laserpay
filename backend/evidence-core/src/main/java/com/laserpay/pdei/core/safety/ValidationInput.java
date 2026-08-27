package com.laserpay.pdei.core.safety;

import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.policy.PolicyView;

import java.util.List;

/**
 * The deterministic ground truth an {@code InvestigationResult} is checked against.
 *
 * <p>Everything here comes from Postgres and the policy engine. Nothing comes from the model, which
 * is the entire point: the result is compared with reality, not with itself.</p>
 */
public record ValidationInput(
        String caseId,
        String disputeId,
        String transactionId,
        String merchantId,
        PolicyView policy,
        List<RequirementView> requirements) {

    public ValidationInput {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
