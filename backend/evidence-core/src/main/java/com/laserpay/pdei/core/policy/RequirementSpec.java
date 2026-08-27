package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RequirementStrength;

/**
 * One evidence requirement as declared by a policy version (a row of
 * {@code pdei.evidence_requirements}).
 *
 * @param weight            scoring weight; defaults to {@link RequirementStrength#weight()}
 *                          (MANDATORY=3, RECOMMENDED=2, OPTIONAL=1, PROHIBITED=0) but a merchant
 *                          policy may override it
 * @param maxAgeDays        expiry rule: evidence older than this no longer satisfies the
 *                          requirement; {@code null} means it never expires
 * @param provenanceRequired when true, evidence without a verifiable source event cannot satisfy it
 * @param minQualityScore   extraction/quality floor in [0,1]
 */
public record RequirementSpec(
        EvidenceType type,
        RequirementStrength strength,
        int weight,
        Integer maxAgeDays,
        boolean provenanceRequired,
        double minQualityScore,
        String note) {

    public static RequirementSpec of(EvidenceType type, RequirementStrength strength) {
        return new RequirementSpec(type, strength, strength.weight(),
                DefaultPolicyMatrix.defaultMaxAgeDays(type),
                strength == RequirementStrength.MANDATORY, 0.0d, null);
    }

    public static RequirementSpec of(EvidenceType type, RequirementStrength strength, int maxAgeDays) {
        return new RequirementSpec(type, strength, strength.weight(), maxAgeDays,
                strength == RequirementStrength.MANDATORY, 0.0d, null);
    }

    public boolean isMandatory() {
        return strength == RequirementStrength.MANDATORY;
    }

    public boolean isRecommended() {
        return strength == RequirementStrength.RECOMMENDED;
    }

    public boolean isProhibited() {
        return strength == RequirementStrength.PROHIBITED;
    }

    /** Weight actually used by the readiness formula. */
    public int effectiveWeight() {
        return weight > 0 ? weight : (strength == null ? 0 : strength.weight());
    }
}
