package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.ReadinessBand;

import java.util.List;

/**
 * Result of activity 2, {@code gatherEvidence}: what is currently attached to the transaction and
 * how ready it makes the case.
 *
 * <p>The numbers come from {@code evidence-core}'s deterministic {@code ReadinessEngine}. Nothing
 * here is an opinion, and nothing here came from a model.</p>
 */
public record EvidenceReport(
        String caseId,
        String transactionId,
        List<String> evidenceIds,
        int evidenceCount,
        int usableEvidenceCount,
        int readinessScore,
        ReadinessBand readinessBand,
        boolean allMandatorySatisfied,
        int unsatisfiedMandatoryCount,
        double deterministicConfidence,
        String policyVersionId) {

    public EvidenceReport {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public boolean hasEvidence() {
        return usableEvidenceCount > 0;
    }
}
