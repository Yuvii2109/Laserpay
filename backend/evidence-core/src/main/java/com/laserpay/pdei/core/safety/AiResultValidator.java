package com.laserpay.pdei.core.safety;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.core.model.Citation;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.model.SafetyVerdict;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.util.CoreErrors;
import com.laserpay.pdei.core.util.Text;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The validation gate of platform contract 9.3.
 *
 * <p>An {@code InvestigationResult} is rejected when ANY of these hold:</p>
 * <ol>
 *   <li>an {@code evidenceId} in {@code supportingEvidence} or {@code citations} does not exist in
 *       Postgres;</li>
 *   <li>an evidence item is not linked to this case's transaction;</li>
 *   <li>{@code recommendedAction} is not permitted by the applicable policy;</li>
 *   <li>{@code confidence < policy.autoPrepareMinConfidence} and the action is
 *       {@code PREPARE_REPRESENTMENT};</li>
 *   <li>{@code contradictions.length > policy.maxContradictions} and the action is
 *       {@code PREPARE_REPRESENTMENT};</li>
 *   <li>a prohibited evidence type appears in {@code supportingEvidence};</li>
 *   <li>{@code classification} is {@code DEFENDABLE} while a MANDATORY requirement is unsatisfied.</li>
 * </ol>
 *
 * <p>Rules 1 and 2 are the anti-hallucination rules: they are the reason the model cannot invent a
 * document or borrow one from another merchant's transaction. Any evidence id that fails them is
 * reported in {@code unsupportedClaims} together with the claim it was cited for, and counted into
 * {@code pdei_ai_unsupported_claims_total}.</p>
 *
 * <p>Any rejection produces {@code SafetyDecision.DENY}, which routes the case to
 * {@code AWAITING_HUMAN_REVIEW}. The validator never mutates anything.</p>
 */
public class AiResultValidator {

    private static final Logger log = LoggerFactory.getLogger(AiResultValidator.class);
    private static final String METRIC_UNSUPPORTED_CLAIMS = "pdei_ai_unsupported_claims_total";

    public static final String RULE_1_UNKNOWN_EVIDENCE = "RULE_1_UNKNOWN_EVIDENCE";
    public static final String RULE_2_EVIDENCE_NOT_LINKED = "RULE_2_EVIDENCE_NOT_LINKED";
    public static final String RULE_3_ACTION_NOT_PERMITTED = "RULE_3_ACTION_NOT_PERMITTED";
    public static final String RULE_4_CONFIDENCE_BELOW_THRESHOLD = "RULE_4_CONFIDENCE_BELOW_THRESHOLD";
    public static final String RULE_5_TOO_MANY_CONTRADICTIONS = "RULE_5_TOO_MANY_CONTRADICTIONS";
    public static final String RULE_6_PROHIBITED_EVIDENCE_TYPE = "RULE_6_PROHIBITED_EVIDENCE_TYPE";
    public static final String RULE_7_DEFENDABLE_WITH_UNSATISFIED_MANDATORY =
            "RULE_7_DEFENDABLE_WITH_UNSATISFIED_MANDATORY";

    private final EvidenceRepositoryPort evidence;
    private final MeterRegistry meterRegistry;

    public AiResultValidator(EvidenceRepositoryPort evidence, MeterRegistry meterRegistry) {
        this.evidence = evidence;
        this.meterRegistry = meterRegistry;
    }

    public SafetyVerdict validate(InvestigationResult result, ValidationInput input) {
        CoreErrors.requireValue(result, "result");
        CoreErrors.requireValue(input, "input");

        List<String> reasons = new ArrayList<>();
        List<String> unsupportedClaims = new ArrayList<>();
        PolicyView policy = input.policy();

        List<String> referenced = result.allReferencedEvidenceIds();
        Map<String, EvidenceView> known = new LinkedHashMap<>();
        evidence.findByIds(referenced).forEach(view -> known.put(view.evidenceId(), view));

        // Rule 1 - the evidence must exist at all.
        Set<String> unknown = new LinkedHashSet<>();
        for (String evidenceId : referenced) {
            if (!known.containsKey(evidenceId)) {
                unknown.add(evidenceId);
            }
        }
        if (!unknown.isEmpty()) {
            reasons.add(RULE_1_UNKNOWN_EVIDENCE + ": evidence ids do not exist: " + unknown);
            unsupportedClaims.addAll(claimsFor(result, unknown, "evidence does not exist"));
        }

        // Rule 2 - the evidence must belong to this case's transaction.
        Set<String> foreign = new LinkedHashSet<>();
        for (EvidenceView view : known.values()) {
            if (input.transactionId() != null && !input.transactionId().equals(view.transactionId())) {
                foreign.add(view.evidenceId());
            }
        }
        if (!foreign.isEmpty()) {
            reasons.add(RULE_2_EVIDENCE_NOT_LINKED + ": evidence is not linked to transaction "
                    + input.transactionId() + ": " + foreign);
            unsupportedClaims.addAll(claimsFor(result, foreign, "evidence belongs to another transaction"));
        }

        // Rule 3 - the action must be permitted by the policy in force.
        RecommendedAction action = result.recommendedAction();
        if (policy == null || !policy.permits(action)) {
            reasons.add(RULE_3_ACTION_NOT_PERMITTED + ": action " + action + " is not permitted by policy "
                    + (policy == null ? "(none applicable)" : policy.policyVersionId()));
        }

        if (action == RecommendedAction.PREPARE_REPRESENTMENT && policy != null) {
            // Rule 4 - automated preparation needs the policy confidence floor.
            if (result.confidence() < policy.autoPrepareMinConfidence()) {
                reasons.add(RULE_4_CONFIDENCE_BELOW_THRESHOLD + ": confidence " + result.confidence()
                        + " is below autoPrepareMinConfidence " + policy.autoPrepareMinConfidence());
            }
            // Rule 5 - and a package that contradicts itself may not be prepared automatically.
            if (result.contradictions().size() > policy.maxContradictions()) {
                reasons.add(RULE_5_TOO_MANY_CONTRADICTIONS + ": " + result.contradictions().size()
                        + " contradictions exceed maxContradictions " + policy.maxContradictions());
            }
        }

        // Rule 6 - prohibited evidence types must never be leaned on.
        Set<EvidenceType> prohibited = new LinkedHashSet<>();
        for (String evidenceId : result.supportingEvidence()) {
            EvidenceView view = known.get(evidenceId);
            if (view != null && policy != null && policy.isProhibited(view.type())) {
                prohibited.add(view.type());
            }
        }
        if (!prohibited.isEmpty()) {
            reasons.add(RULE_6_PROHIBITED_EVIDENCE_TYPE + ": supporting evidence includes prohibited types "
                    + prohibited);
        }

        // Rule 7 - a case cannot be DEFENDABLE while a mandatory requirement is unmet.
        List<RequirementView> unsatisfied = input.requirements().stream()
                .filter(RequirementView::isMandatory)
                .filter(requirement -> !requirement.satisfied())
                .toList();
        if (result.classification() == InvestigationClassification.DEFENDABLE && !unsatisfied.isEmpty()) {
            reasons.add(RULE_7_DEFENDABLE_WITH_UNSATISFIED_MANDATORY
                    + ": classified DEFENDABLE while mandatory requirements are unsatisfied: "
                    + unsatisfied.stream().map(requirement -> requirement.type().name()).toList());
        }

        if (reasons.isEmpty()) {
            return SafetyVerdict.allow();
        }
        countUnsupportedClaims(unsupportedClaims.size());
        log.warn("AI result for investigation {} denied by {} rule(s): {}",
                result.investigationId(), reasons.size(), reasons);
        return SafetyVerdict.deny(reasons, unsupportedClaims);
    }

    /**
     * Turn rejected evidence ids into readable unsupported claims, quoting the citation text when the
     * model supplied one. This is what a reviewer sees: the sentence the model wanted to assert, and
     * the reason it could not be backed.
     */
    private static List<String> claimsFor(InvestigationResult result, Set<String> evidenceIds, String why) {
        List<String> claims = new ArrayList<>();
        for (Citation citation : result.citations()) {
            if (citation.evidenceId() != null && evidenceIds.contains(citation.evidenceId())) {
                claims.add(Text.abbreviate(citation.claim(), 240) + " [" + citation.evidenceId() + ": " + why + "]");
            }
        }
        for (String evidenceId : evidenceIds) {
            boolean cited = result.citations().stream()
                    .anyMatch(citation -> evidenceId.equals(citation.evidenceId()));
            if (!cited) {
                claims.add("supportingEvidence " + evidenceId + " [" + why + "]");
            }
        }
        return claims;
    }

    private void countUnsupportedClaims(int count) {
        if (meterRegistry == null || count <= 0) {
            return;
        }
        try {
            meterRegistry.counter(METRIC_UNSUPPORTED_CLAIMS).increment(count);
        } catch (RuntimeException e) {
            // metrics never block a safety decision
        }
    }
}
