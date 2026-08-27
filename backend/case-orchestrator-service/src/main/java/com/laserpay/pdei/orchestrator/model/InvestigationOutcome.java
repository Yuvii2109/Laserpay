package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;

import java.util.List;

/**
 * Result of activity 6, {@code investigate}: a PROPOSAL, never a decision.
 *
 * <p>The workflow deliberately carries only the flattened summary rather than the whole
 * {@code InvestigationResult}. Two reasons: the full result is already persisted to
 * {@code pdei.investigations} by the activity, and keeping large payloads out of the workflow
 * history is what keeps continue-as-new cheap.</p>
 *
 * @param aiUsed    false when the deterministic investigator produced this
 * @param provider  {@code deterministic} or whatever the Python service reported
 */
public record InvestigationOutcome(
        String investigationId,
        String caseId,
        InvestigationClassification classification,
        double confidence,
        RecommendedAction recommendedAction,
        List<String> supportingEvidence,
        List<EvidenceType> missingEvidence,
        int contradictionCount,
        String reasoningSummary,
        boolean aiUsed,
        String provider,
        String model,
        long latencyMs) {

    public InvestigationOutcome {
        supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
    }
}
