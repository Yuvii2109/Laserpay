package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.EvidenceType;

import java.util.List;

/**
 * The {@code policyConstraints} object of {@link InvestigationContext} (platform contract 9.1).
 * Field names are exactly {@code autoPrepareMinConfidence}, {@code maxContradictions},
 * {@code prohibitedEvidenceTypes}.
 */
public record PolicyConstraints(
        double autoPrepareMinConfidence,
        int maxContradictions,
        List<EvidenceType> prohibitedEvidenceTypes) {

    public PolicyConstraints {
        prohibitedEvidenceTypes = prohibitedEvidenceTypes == null
                ? List.of() : List.copyOf(prohibitedEvidenceTypes);
    }
}
