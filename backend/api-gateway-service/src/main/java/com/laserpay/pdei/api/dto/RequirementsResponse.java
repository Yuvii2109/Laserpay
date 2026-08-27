package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.policy.RequirementSpec;
import java.util.List;

/**
 * {@code GET /requirements?reasonCode=...}, {@code GET /policies/{policyId}/requirements} and
 * {@code GET /ai-tools/requirements?reasonCode=...}.
 *
 * <p>{@code policyVersionId} names which version answered. Without it the caller cannot tell a
 * merchant's published matrix from the seeded platform default, and those two carry very different
 * weight when a decision is questioned later. {@code defaultPolicy} says so explicitly.</p>
 *
 * <p>When {@code reasonCode} is null the answer is the merchant baseline profile: the union of
 * MANDATORY requirements across the reason codes that merchant actually receives.</p>
 */
public record RequirementsResponse(
        String merchantId,
        DisputeReasonCode reasonCode,
        String policyId,
        String policyVersionId,
        boolean defaultPolicy,
        List<RequirementSpec> requirements,
        int mandatoryCount) {

    public RequirementsResponse {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public static RequirementsResponse of(String merchantId, DisputeReasonCode reasonCode,
                                          String policyId, String policyVersionId, boolean defaultPolicy,
                                          List<RequirementSpec> requirements) {
        List<RequirementSpec> safe = requirements == null ? List.of() : List.copyOf(requirements);
        int mandatory = (int) safe.stream().filter(RequirementSpec::isMandatory).count();
        return new RequirementsResponse(merchantId, reasonCode, policyId, policyVersionId,
                defaultPolicy, safe, mandatory);
    }
}
