package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RecommendedAction;

import java.util.List;
import java.util.Set;

/**
 * Proposed content of a new policy version, as submitted by {@code PUT /api/v1/policies/{policyId}}.
 * A draft is never stored: {@code PolicyVersionService} turns it into an immutable {@link PolicyView}.
 */
public record PolicyDraft(
        String merchantId,
        DisputeReasonCode reasonCode,
        List<RequirementSpec> requirements,
        Set<RecommendedAction> permittedActions,
        Set<EvidenceType> prohibitedEvidenceTypes,
        double autoPrepareMinConfidence,
        int maxContradictions,
        int minReadinessScoreForAutoPrepare,
        long humanReviewAboveAmountMinor,
        String currency,
        boolean autoSubmitEnabled,
        int responseWindowDays,
        int expiringSoonDays) {

    public PolicyDraft {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        permittedActions = permittedActions == null
                ? DefaultPolicyMatrix.DEFAULT_PERMITTED_ACTIONS : Set.copyOf(permittedActions);
        prohibitedEvidenceTypes = prohibitedEvidenceTypes == null ? Set.of() : Set.copyOf(prohibitedEvidenceTypes);
    }

    /** A draft pre-filled from the seeded matrix, ready to be edited by a merchant. */
    public static PolicyDraft fromDefaults(String merchantId, DisputeReasonCode reasonCode) {
        return new PolicyDraft(
                merchantId,
                reasonCode,
                DefaultPolicyMatrix.requirements(reasonCode),
                DefaultPolicyMatrix.DEFAULT_PERMITTED_ACTIONS,
                Set.of(),
                DefaultPolicyMatrix.DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE,
                DefaultPolicyMatrix.DEFAULT_MAX_CONTRADICTIONS,
                DefaultPolicyMatrix.DEFAULT_MIN_READINESS_FOR_AUTO_PREPARE,
                DefaultPolicyMatrix.DEFAULT_HUMAN_REVIEW_ABOVE_AMOUNT_MINOR,
                DefaultPolicyMatrix.DEFAULT_CURRENCY,
                DefaultPolicyMatrix.DEFAULT_AUTO_SUBMIT_ENABLED,
                DefaultPolicyMatrix.DEFAULT_RESPONSE_WINDOW_DAYS,
                DefaultPolicyMatrix.DEFAULT_EXPIRING_SOON_DAYS);
    }
}
