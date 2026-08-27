package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RequirementStrength;

import java.util.List;

/**
 * One evidence requirement resolved against the evidence actually present on a transaction.
 *
 * <p>{@code type}, {@code strength} and {@code satisfied} are the fields serialised into
 * {@link InvestigationContext#requirements()} (platform contract 9.1).</p>
 */
public record RequirementView(
        EvidenceType type,
        RequirementStrength strength,
        boolean satisfied,
        List<String> satisfyingEvidenceIds,
        int weight,
        String note) {

    public RequirementView {
        satisfyingEvidenceIds = satisfyingEvidenceIds == null ? List.of() : List.copyOf(satisfyingEvidenceIds);
    }

    public boolean isMandatory() {
        return strength == RequirementStrength.MANDATORY;
    }

    public boolean isRecommended() {
        return strength == RequirementStrength.RECOMMENDED;
    }
}
