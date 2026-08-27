package com.laserpay.pdei.core.ai;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.core.model.Citation;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.InvestigationContext;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.ModelMetadata;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.RequirementView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces an {@link InvestigationResult} with no model involved.
 *
 * <p>Two uses:</p>
 * <ul>
 *   <li>the fallback when the AI service is unavailable or its circuit is open, so a provider outage
 *       degrades quality rather than stopping dispute handling;</li>
 *   <li>the resolution for cases that admission control short-circuits.</li>
 * </ul>
 *
 * <p>It never invents anything. Every claim cites a specific evidence id that the platform itself put
 * into the context, so it passes the same validator the model output must pass. Results are tagged
 * {@code provider = "deterministic"} in {@link ModelMetadata} so the UI, the audit trail and the
 * funnel metrics can always tell the two apart.</p>
 */
public class DeterministicInvestigator {

    /** Confidence claimed when every mandatory requirement is met and nothing contradicts. */
    public static final double COMPLETE_CONFIDENCE = 1.0d;
    /** Confidence claimed when evidence is incomplete - deliberately below any auto-prepare floor. */
    public static final double PARTIAL_CONFIDENCE = 0.55d;
    public static final double EMPTY_CONFIDENCE = 0.20d;

    public InvestigationResult investigate(InvestigationContext context) {
        return investigate(context, 1, 0L);
    }

    public InvestigationResult investigate(InvestigationContext context, int attempt, long latencyMs) {
        List<RequirementView> unsatisfiedMandatory = context.requirements().stream()
                .filter(RequirementView::isMandatory)
                .filter(requirement -> !requirement.satisfied())
                .toList();
        List<String> supporting = supportingEvidence(context);
        List<EvidenceType> missing = missingEvidence(context, unsatisfiedMandatory);
        boolean hasContradictions = !context.contradictions().isEmpty();
        boolean hasEvidence = !context.evidence().isEmpty();

        InvestigationClassification classification;
        RecommendedAction action;
        double confidence;
        String summary;

        if (!hasEvidence) {
            classification = InvestigationClassification.INSUFFICIENT_EVIDENCE;
            action = RecommendedAction.ACCEPT_LIABILITY;
            confidence = EMPTY_CONFIDENCE;
            summary = "No evidence is attached to transaction " + context.transactionId()
                    + ", so no representment can be supported.";
        } else if (hasContradictions) {
            classification = InvestigationClassification.AMBIGUOUS;
            action = RecommendedAction.ESCALATE_TO_HUMAN;
            confidence = PARTIAL_CONFIDENCE;
            summary = context.contradictions().size() + " contradiction(s) were detected between the"
                    + " merchant records; a human must resolve them before any submission.";
        } else if (!unsatisfiedMandatory.isEmpty()) {
            classification = InvestigationClassification.INSUFFICIENT_EVIDENCE;
            action = RecommendedAction.GATHER_MORE_EVIDENCE;
            confidence = PARTIAL_CONFIDENCE;
            summary = unsatisfiedMandatory.size() + " mandatory requirement(s) are unsatisfied: "
                    + unsatisfiedMandatory.stream().map(requirement -> requirement.type().name()).toList();
        } else {
            classification = InvestigationClassification.DEFENDABLE;
            action = RecommendedAction.PREPARE_REPRESENTMENT;
            confidence = COMPLETE_CONFIDENCE;
            summary = "Every mandatory requirement for " + context.reasonCode()
                    + " is satisfied by verified evidence and no contradictions were found.";
        }

        return new InvestigationResult(
                context.investigationId(),
                classification,
                confidence,
                supporting,
                missing,
                context.contradictions(),
                summary,
                narrative(context, classification, supporting),
                action,
                citations(context),
                ModelMetadata.deterministic(latencyMs, attempt));
    }

    /** A plain, verifiable narrative. Every sentence maps to a fact already present in the context. */
    public String narrative(InvestigationContext context, InvestigationClassification classification,
                            List<String> supporting) {
        StringBuilder text = new StringBuilder();
        text.append("Transaction ").append(context.transactionId())
                .append(" is disputed under ").append(context.reasonCode()).append('.');
        if (context.disputeAmount() != null) {
            text.append(" Disputed value: ").append(context.disputeAmount().amountMinor())
                    .append(" minor units ").append(context.disputeAmount().currency()).append('.');
        }
        text.append(" Deterministic assessment: ").append(classification).append('.');
        if (!supporting.isEmpty()) {
            text.append(" Supporting evidence: ").append(String.join(", ", supporting)).append('.');
        }
        for (RequirementView requirement : context.requirements()) {
            if (requirement.isMandatory() && requirement.satisfied()
                    && !requirement.satisfyingEvidenceIds().isEmpty()) {
                text.append(' ').append(requirement.type()).append(" is evidenced by ")
                        .append(requirement.satisfyingEvidenceIds().get(0)).append('.');
            }
        }
        if (!context.contradictions().isEmpty()) {
            text.append(" Unresolved conflicts: ")
                    .append(context.contradictions().stream()
                            .map(contradiction -> String.valueOf(contradiction.detail()))
                            .toList())
                    .append('.');
        }
        return text.toString();
    }

    private static List<String> supportingEvidence(InvestigationContext context) {
        Set<String> ids = new LinkedHashSet<>();
        for (RequirementView requirement : context.requirements()) {
            if (requirement.satisfied()) {
                ids.addAll(requirement.satisfyingEvidenceIds());
            }
        }
        if (ids.isEmpty()) {
            context.evidence().stream()
                    .filter(EvidenceView::isUsable)
                    .map(EvidenceView::evidenceId)
                    .forEach(ids::add);
        }
        return List.copyOf(ids);
    }

    private static List<EvidenceType> missingEvidence(InvestigationContext context,
                                                      List<RequirementView> unsatisfiedMandatory) {
        Set<EvidenceType> missing = new LinkedHashSet<>();
        unsatisfiedMandatory.forEach(requirement -> missing.add(requirement.type()));
        for (ReadinessGap gap : context.gaps()) {
            if (gap.evidenceType() != null) {
                missing.add(gap.evidenceType());
            }
        }
        return List.copyOf(missing);
    }

    private static List<Citation> citations(InvestigationContext context) {
        List<Citation> citations = new ArrayList<>();
        for (RequirementView requirement : context.requirements()) {
            if (!requirement.satisfied()) {
                continue;
            }
            for (String evidenceId : requirement.satisfyingEvidenceIds()) {
                citations.add(new Citation(
                        requirement.type() + " requirement is satisfied by evidence " + evidenceId,
                        evidenceId));
            }
        }
        return List.copyOf(citations);
    }
}
