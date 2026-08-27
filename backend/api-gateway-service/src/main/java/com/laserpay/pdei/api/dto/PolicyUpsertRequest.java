package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.core.policy.DefaultPolicyMatrix;
import com.laserpay.pdei.core.policy.PolicyDraft;
import com.laserpay.pdei.core.policy.RequirementSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/**
 * {@code PUT /policies/{policyId}}: publish a new immutable version.
 *
 * <p>PUT here does not overwrite. {@code PolicyVersionService.publish} appends a version and closes
 * the previous interval, so a decision made months ago can still be replayed against the rules that
 * were actually in force. The verb is PUT because the caller addresses the policy, not a version.</p>
 *
 * <p>Any field left null falls back to the seeded default for that reason code, so a merchant can
 * publish a two-line change without restating the whole matrix.</p>
 *
 * @param humanReviewAboveAmountMinor minor units, matching the money rule; there is no decimal form
 */
public record PolicyUpsertRequest(
        @NotBlank(message = "merchantId is required")
        @Pattern(regexp = "^MER-[A-Za-z0-9_-]+$", message = "must be a MER- prefixed id")
        String merchantId,

        DisputeReasonCode reasonCode,

        @Valid
        List<RequirementInput> requirements,

        Set<RecommendedAction> permittedActions,

        Set<EvidenceType> prohibitedEvidenceTypes,

        @DecimalMin(value = "0.0", message = "autoPrepareMinConfidence must be between 0.0 and 1.0")
        @DecimalMax(value = "1.0", message = "autoPrepareMinConfidence must be between 0.0 and 1.0")
        Double autoPrepareMinConfidence,

        @PositiveOrZero(message = "maxContradictions must not be negative")
        Integer maxContradictions,

        @Min(value = 0, message = "minReadinessScoreForAutoPrepare must be between 0 and 100")
        Integer minReadinessScoreForAutoPrepare,

        @PositiveOrZero(message = "humanReviewAboveAmountMinor must not be negative")
        Long humanReviewAboveAmountMinor,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency,

        Boolean autoSubmitEnabled,

        @Min(value = 1, message = "responseWindowDays must be at least 1")
        Integer responseWindowDays,

        @Min(value = 1, message = "expiringSoonDays must be at least 1")
        Integer expiringSoonDays,

        @NotBlank(message = "actor is required: a policy change must name its author")
        String actor) {

    /** One requirement row of the merchant's matrix. */
    public record RequirementInput(
            @NotNull(message = "type is required") EvidenceType type,
            @NotNull(message = "strength is required") RequirementStrength strength,
            @PositiveOrZero(message = "weight must not be negative") Integer weight,
            @Min(value = 1, message = "maxAgeDays must be at least 1") Integer maxAgeDays,
            Boolean provenanceRequired,
            @DecimalMin(value = "0.0", message = "minQualityScore must be between 0.0 and 1.0")
            @DecimalMax(value = "1.0", message = "minQualityScore must be between 0.0 and 1.0")
            Double minQualityScore,
            String note) {

        public RequirementSpec toSpec() {
            int effectiveWeight = weight == null ? strength.weight() : weight;
            Integer effectiveMaxAge = maxAgeDays == null
                    ? DefaultPolicyMatrix.defaultMaxAgeDays(type) : maxAgeDays;
            boolean effectiveProvenance = provenanceRequired == null
                    ? strength == RequirementStrength.MANDATORY : provenanceRequired;
            double effectiveQuality = minQualityScore == null ? 0.0d : minQualityScore;
            return new RequirementSpec(type, strength, effectiveWeight, effectiveMaxAge,
                    effectiveProvenance, effectiveQuality, note);
        }
    }

    /**
     * Build the draft evidence-core publishes, filling every unset field from the seeded defaults for
     * this reason code so a partial request is still a complete, replayable policy version.
     */
    public PolicyDraft toDraft() {
        PolicyDraft defaults = PolicyDraft.fromDefaults(merchantId, reasonCode);
        List<RequirementSpec> specs = requirements == null || requirements.isEmpty()
                ? defaults.requirements()
                : requirements.stream().map(RequirementInput::toSpec).toList();
        return new PolicyDraft(
                merchantId,
                reasonCode,
                specs,
                permittedActions == null || permittedActions.isEmpty()
                        ? defaults.permittedActions() : permittedActions,
                prohibitedEvidenceTypes == null ? defaults.prohibitedEvidenceTypes() : prohibitedEvidenceTypes,
                autoPrepareMinConfidence == null
                        ? defaults.autoPrepareMinConfidence() : autoPrepareMinConfidence,
                maxContradictions == null ? defaults.maxContradictions() : maxContradictions,
                minReadinessScoreForAutoPrepare == null
                        ? defaults.minReadinessScoreForAutoPrepare() : minReadinessScoreForAutoPrepare,
                humanReviewAboveAmountMinor == null
                        ? defaults.humanReviewAboveAmountMinor() : humanReviewAboveAmountMinor,
                currency == null || currency.isBlank()
                        ? defaults.currency() : currency.toUpperCase(java.util.Locale.ROOT),
                autoSubmitEnabled == null ? defaults.autoSubmitEnabled() : autoSubmitEnabled,
                responseWindowDays == null ? defaults.responseWindowDays() : responseWindowDays,
                expiringSoonDays == null ? defaults.expiringSoonDays() : expiringSoonDays);
    }
}
