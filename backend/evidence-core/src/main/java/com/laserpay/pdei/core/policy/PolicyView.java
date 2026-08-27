package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.core.model.PolicyConstraints;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable policy version: the requirement matrix plus the automation thresholds that govern
 * what may happen without a human.
 *
 * <p>A version is never edited. {@code PolicyVersionService} publishes a new one and closes the
 * previous interval, so any past decision can be replayed against the policy that was in force.</p>
 */
public record PolicyView(
        String policyId,
        String policyVersionId,
        int version,
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
        int expiringSoonDays,
        String createdBy,
        String checksum,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean defaultPolicy) {

    public PolicyView {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        permittedActions = permittedActions == null ? Set.of() : Set.copyOf(permittedActions);
        prohibitedEvidenceTypes = prohibitedEvidenceTypes == null ? Set.of() : Set.copyOf(prohibitedEvidenceTypes);
    }

    public List<RequirementSpec> mandatory() {
        return requirements.stream().filter(RequirementSpec::isMandatory).toList();
    }

    public Optional<RequirementSpec> requirementFor(EvidenceType type) {
        return requirements.stream().filter(r -> r.type() == type).findFirst();
    }

    public RequirementStrength strengthOf(EvidenceType type) {
        return requirementFor(type).map(RequirementSpec::strength).orElse(null);
    }

    public boolean isProhibited(EvidenceType type) {
        return prohibitedEvidenceTypes.contains(type)
                || requirementFor(type).map(RequirementSpec::isProhibited).orElse(false);
    }

    public boolean permits(RecommendedAction action) {
        return action != null && permittedActions.contains(action);
    }

    public boolean isActiveAt(Instant at) {
        if (at == null) {
            return effectiveTo == null;
        }
        boolean started = effectiveFrom == null || !at.isBefore(effectiveFrom);
        boolean notEnded = effectiveTo == null || at.isBefore(effectiveTo);
        return started && notEnded;
    }

    /** Projection handed to the AI service as {@code policyConstraints} (platform contract 9.1). */
    public PolicyConstraints toConstraints() {
        return new PolicyConstraints(autoPrepareMinConfidence, maxContradictions,
                List.copyOf(prohibitedEvidenceTypes));
    }

    public PolicyView withVersion(String newPolicyVersionId, int newVersion, Instant newEffectiveFrom,
                                  String actor, String newChecksum) {
        return new PolicyView(policyId, newPolicyVersionId, newVersion, merchantId, reasonCode,
                requirements, permittedActions, prohibitedEvidenceTypes, autoPrepareMinConfidence,
                maxContradictions, minReadinessScoreForAutoPrepare, humanReviewAboveAmountMinor,
                currency, autoSubmitEnabled, responseWindowDays, expiringSoonDays, actor, newChecksum,
                newEffectiveFrom, null, false);
    }
}
