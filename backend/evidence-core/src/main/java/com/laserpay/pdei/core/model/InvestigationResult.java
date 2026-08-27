package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;

import java.util.List;

/**
 * Schema-constrained response from {@code POST /v1/investigate}.
 *
 * <p>Field names and order mirror platform contract 9.2 character-for-character:
 * {@code investigationId, classification, confidence, supportingEvidence, missingEvidence,
 * contradictions, reasoningSummary, narrative, recommendedAction, citations, modelMetadata}.
 * The JSON Schema {@code schemas/ai/investigation-result.schema.json} is the referee.</p>
 *
 * <p>{@code missingEvidence} carries evidence TYPES, not ids: missing evidence has no id yet. The
 * referee schema constrains the array to {@code EvidenceType} members, so this side must too -
 * a plain string would let an invented id through Jackson that Pydantic would have rejected.</p>
 *
 * <p>Nothing in this record is trusted: it is a PROPOSAL. Every field is re-checked by
 * {@code core.safety.AiResultValidator} before any state changes.</p>
 */
public record InvestigationResult(
        String investigationId,
        InvestigationClassification classification,
        double confidence,
        List<String> supportingEvidence,
        List<EvidenceType> missingEvidence,
        List<ContradictionView> contradictions,
        String reasoningSummary,
        String narrative,
        RecommendedAction recommendedAction,
        List<Citation> citations,
        ModelMetadata modelMetadata) {

    public InvestigationResult {
        supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Every evidence id the result leans on, from both {@code supportingEvidence} and {@code citations}. */
    public List<String> allReferencedEvidenceIds() {
        return java.util.stream.Stream.concat(
                        supportingEvidence.stream(),
                        citations.stream().map(Citation::evidenceId))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    public InvestigationResult withModelMetadata(ModelMetadata metadata) {
        return new InvestigationResult(investigationId, classification, confidence, supportingEvidence,
                missingEvidence, contradictions, reasoningSummary, narrative, recommendedAction,
                citations, metadata);
    }
}
