package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request payload sent to {@code POST /v1/investigate} on the Python AI reasoning service.
 *
 * <p>Field names and order mirror platform contract 9.1 character-for-character:
 * {@code investigationId, caseId, disputeId, merchantId, transactionId, reasonCode, disputeAmount,
 * deadlineAt, transactionSummary, evidence, requirements, gaps, contradictions, policyConstraints,
 * timeline, historicalContext}. The mirror types are
 * {@code pdei_ai.models.investigation.InvestigationContext} (Python) and {@code types/ai.ts}
 * (TypeScript); do not add or rename a field here without changing all three.</p>
 *
 * <p>This is the ONLY view of the domain the model ever receives. It is curated on purpose: the AI
 * service has no database access and can only widen its view through the read-only
 * {@code /api/v1/ai-tools/*} endpoints.</p>
 */
public record InvestigationContext(
        String investigationId,
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        DisputeReasonCode reasonCode,
        Money disputeAmount,
        Instant deadlineAt,
        Map<String, Object> transactionSummary,
        List<EvidenceView> evidence,
        List<RequirementView> requirements,
        List<ReadinessGap> gaps,
        List<ContradictionView> contradictions,
        PolicyConstraints policyConstraints,
        List<TimelineEntry> timeline,
        HistoricalContext historicalContext) {

    public InvestigationContext {
        transactionSummary = transactionSummary == null ? Map.of() : Map.copyOf(transactionSummary);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    /** Hours left before the representment deadline, or {@code Long.MAX_VALUE} when unknown. */
    public long hoursUntilDeadline(Instant now) {
        if (deadlineAt == null || now == null) {
            return Long.MAX_VALUE;
        }
        return java.time.Duration.between(now, deadlineAt).toHours();
    }

    public boolean pastDeadline(Instant now) {
        return deadlineAt != null && now != null && now.isAfter(deadlineAt);
    }
}
