package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.ModelMetadata;
import com.laserpay.pdei.core.model.SafetyVerdict;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /investigations/{investigationId}}.
 *
 * <p>Three things are shown side by side on purpose, and the ordering is the argument:</p>
 *
 * <ol>
 *   <li>{@code result} is what the model proposed. It is a proposal, nothing more.</li>
 *   <li>{@code verdict} is what the deterministic gate decided about that proposal, including the
 *       rule codes it failed and any claim it could not support.</li>
 *   <li>{@code findings} are the per-claim validation outcomes, so an operator can see exactly which
 *       sentence failed to check out rather than only that something did.</li>
 * </ol>
 *
 * <p>{@code deterministic} is true when no model was involved at all: the case was short-circuited by
 * admission control and answered by {@code DeterministicInvestigator}. That is a normal, common and
 * cheap outcome, not a degraded one.</p>
 */
public record InvestigationResponse(
        String investigationId,
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        InvestigationClassification classification,
        double confidence,
        RecommendedAction recommendedAction,
        SafetyDecision safetyDecision,
        boolean deterministic,
        String reasoningSummary,
        String narrative,
        InvestigationResult result,
        SafetyVerdict verdict,
        List<FindingView> findings,
        ModelMetadata modelMetadata,
        Instant startedAt,
        Instant completedAt) {

    public InvestigationResponse {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * One validated claim from {@code investigation_findings}.
     *
     * @param validated      false means the claim could not be tied to a real, linked evidence row
     * @param validationError why it failed, when it did
     */
    public record FindingView(
            String findingId,
            int sequenceNo,
            String findingType,
            String evidenceId,
            String relatedEvidenceId,
            String field,
            String claim,
            String detail,
            boolean validated,
            String validationError) {
    }
}
