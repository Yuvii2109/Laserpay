package com.laserpay.pdei.core.safety;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.SafetyVerdict;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyView;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The single decision point between an AI proposal and any state change.
 *
 * <p>It combines three independent checks:</p>
 * <ol>
 *   <li>{@link AiResultValidator} - the seven hard rejection rules of contract 9.3. Any failure is
 *       a final DENY; nothing downstream can override it.</li>
 *   <li>{@link PolicyEngine#evaluateAction} - the automation thresholds of the policy in force.</li>
 *   <li>Escalation heuristics - value at stake, readiness band, critical gaps and actions that are
 *       human decisions by nature.</li>
 * </ol>
 *
 * <p>The result is one of three decisions:</p>
 * <ul>
 *   <li>{@code ALLOW} - the deterministic layer agrees; automation may proceed.</li>
 *   <li>{@code ALLOW_WITH_REVIEW} - the proposal is not unsafe, but a human signs it off. This is the
 *       default for anything expensive, ambiguous, or asking a human to accept liability.</li>
 *   <li>{@code DENY} - route to {@code AWAITING_HUMAN_REVIEW} and never act on the proposal.</li>
 * </ul>
 *
 * <p>Every gate decision is audited, so "why did the machine do that" always has an answer.</p>
 */
public class SafetyGate {

    private static final Logger log = LoggerFactory.getLogger(SafetyGate.class);
    private static final String METRIC_POLICY_GATE = "pdei_policy_gate_total";
    private static final String ENTITY_TYPE = "INVESTIGATION";

    /** Above this confidence an otherwise clean PREPARE_REPRESENTMENT can run unattended. */
    public static final double DEFAULT_UNATTENDED_CONFIDENCE = 0.95d;

    private final AiResultValidator validator;
    private final PolicyEngine policyEngine;
    private final AuditRecorder audit;
    private final MeterRegistry meterRegistry;
    private final double unattendedConfidence;

    public SafetyGate(AiResultValidator validator, PolicyEngine policyEngine, AuditRecorder audit,
                      MeterRegistry meterRegistry, double unattendedConfidence) {
        this.validator = validator;
        this.policyEngine = policyEngine;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.unattendedConfidence = unattendedConfidence <= 0.0d
                ? DEFAULT_UNATTENDED_CONFIDENCE : unattendedConfidence;
    }

    public SafetyVerdict evaluate(InvestigationResult result, GateInput input) {
        SafetyVerdict validation = validator.validate(result, input.toValidationInput());
        if (validation.isDenied()) {
            record(result, input, validation);
            return validation;
        }

        PolicyView policy = input.policy();
        RecommendedAction action = result.recommendedAction();
        List<String> reviewReasons = new ArrayList<>();

        int readinessScore = input.readiness() == null ? 0 : input.readiness().score();
        var policyDecision = policyEngine.evaluateAction(policy, action, result.confidence(),
                result.contradictions().size(), readinessScore, input.disputeAmount());
        if (!policyDecision.permitted()) {
            // The hard rules already passed, so these are threshold failures: a human decides,
            // rather than the platform refusing to look at the case at all.
            reviewReasons.addAll(policyDecision.reasons());
        }

        if (action != RecommendedAction.PREPARE_REPRESENTMENT) {
            reviewReasons.add("action " + action + " is a human decision by nature");
        }
        if (result.confidence() < unattendedConfidence) {
            reviewReasons.add("confidence " + result.confidence() + " is below the unattended threshold "
                    + unattendedConfidence);
        }
        if (policyEngine.exceedsHumanReviewThreshold(policy, input.disputeAmount())) {
            reviewReasons.add("dispute value is above the human review threshold");
        }
        if (input.pastDeadline()) {
            reviewReasons.add("representment deadline has already passed");
        }
        if (input.readiness() != null) {
            long critical = input.readiness().gaps().stream()
                    .filter(gap -> gap.severity() == GapSeverity.CRITICAL)
                    .count();
            if (critical > 0) {
                reviewReasons.add(critical + " critical readiness gap(s) remain");
            }
            if (!input.readiness().allMandatorySatisfied()) {
                reviewReasons.add("mandatory evidence requirements are still unsatisfied");
            }
        }

        SafetyVerdict verdict = reviewReasons.isEmpty()
                ? SafetyVerdict.allow()
                : SafetyVerdict.allowWithReview(List.copyOf(reviewReasons));
        record(result, input, verdict);
        return verdict;
    }

    /**
     * Deterministic gate for the no-AI path: the same thresholds applied to a proposal the platform
     * generated itself. Used when admission control short-circuits the model.
     */
    public SafetyVerdict evaluateDeterministic(RecommendedAction action, GateInput input) {
        PolicyView policy = input.policy();
        int readinessScore = input.readiness() == null ? 0 : input.readiness().score();
        int contradictions = input.readiness() == null ? 0 : input.readiness().contradictions().size();
        var decision = policyEngine.evaluateAction(policy, action, 1.0d, contradictions, readinessScore,
                input.disputeAmount());
        if (decision.permitted() && action == RecommendedAction.PREPARE_REPRESENTMENT
                && !input.pastDeadline()) {
            countDecision(SafetyDecision.ALLOW);
            return SafetyVerdict.allow();
        }
        List<String> reasons = new ArrayList<>(decision.reasons());
        if (input.pastDeadline()) {
            reasons.add("representment deadline has already passed");
        }
        if (reasons.isEmpty()) {
            reasons.add("action " + action + " requires human confirmation");
        }
        countDecision(SafetyDecision.ALLOW_WITH_REVIEW);
        return SafetyVerdict.allowWithReview(reasons);
    }

    private void record(InvestigationResult result, GateInput input, SafetyVerdict verdict) {
        countDecision(verdict.decision());
        try {
            audit.record(AuditCommand.of(ENTITY_TYPE, result.investigationId(), input.merchantId(),
                            "SAFETY_GATE_" + verdict.decision(), "SAFETY_GATE", ActorType.SYSTEM)
                    .withBefore(result)
                    .withAfter(verdict));
        } catch (RuntimeException e) {
            log.error("failed to audit safety gate decision for investigation {}: {}",
                    result.investigationId(), e.toString());
        }
        if (verdict.isDenied()) {
            log.warn("safety gate DENY for case {}: {}", input.caseId(), verdict.reasons());
        }
    }

    private void countDecision(SafetyDecision decision) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(METRIC_POLICY_GATE, "decision", String.valueOf(decision)).increment();
        } catch (RuntimeException e) {
            // metrics never block a safety decision
        }
    }
}
