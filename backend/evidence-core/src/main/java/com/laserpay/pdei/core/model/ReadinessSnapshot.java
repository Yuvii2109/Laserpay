package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RequirementStrength;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic readiness state of a transaction, produced by
 * {@code com.laserpay.pdei.core.readiness.ReadinessEngine} using the formula in platform contract 7.
 */
public record ReadinessSnapshot(
        String snapshotId,
        String transactionId,
        String merchantId,
        DisputeReasonCode reasonCode,
        int score,
        ReadinessBand band,
        double baseScore,
        int penaltyPoints,
        List<RequirementView> requirements,
        List<ReadinessGap> gaps,
        List<ContradictionView> contradictions,
        String policyVersionId,
        Instant computedAt) {

    public ReadinessSnapshot {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
    }

    public boolean isReady() {
        return band == ReadinessBand.READY;
    }

    public List<RequirementView> unsatisfiedMandatory() {
        return requirements.stream()
                .filter(r -> r.strength() == RequirementStrength.MANDATORY && !r.satisfied())
                .toList();
    }

    public boolean allMandatorySatisfied() {
        return unsatisfiedMandatory().isEmpty();
    }

    /**
     * Deterministic confidence of the non-AI path, in [0,1]. Feeds the {@code deterministicConfidence}
     * term of the admission priority formula (platform contract 9.4).
     */
    public double deterministicConfidence() {
        double confidence = score / 100.0d;
        if (!contradictions.isEmpty()) {
            confidence = confidence * 0.5d;
        }
        if (!allMandatorySatisfied()) {
            confidence = confidence * 0.75d;
        }
        return Math.max(0.0d, Math.min(1.0d, confidence));
    }
}
