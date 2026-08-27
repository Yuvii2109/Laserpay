package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.PolicyConstraints;
import com.laserpay.pdei.core.spi.PolicyRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps dispute scenarios to evidence requirements and to permitted actions.
 *
 * <p>This is the "disposes" half of "AI proposes; policy disposes". Nothing in this class consults
 * the model, and no model output can widen what it allows.</p>
 *
 * <p>Resolution order for a policy: the merchant version in force for that reason code, then the
 * merchant version with no reason code (their house policy), then the seeded
 * {@link DefaultPolicyMatrix}. The fallback is always deterministic, so the engine keeps working
 * with an empty policy table.</p>
 */
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final PolicyRepositoryPort policies;
    private final Clocks clock;

    public PolicyEngine(PolicyRepositoryPort policies, Clocks clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /** The policy version in force right now for this merchant and reason code. Never null. */
    public PolicyView applicablePolicy(String merchantId, DisputeReasonCode reasonCode) {
        return applicablePolicy(merchantId, reasonCode, clock.now());
    }

    public PolicyView applicablePolicy(String merchantId, DisputeReasonCode reasonCode, Instant at) {
        if (policies != null && merchantId != null) {
            try {
                Optional<PolicyView> exact = policies.findActive(merchantId, reasonCode, at);
                if (exact.isPresent()) {
                    return exact.get();
                }
                if (reasonCode != null) {
                    Optional<PolicyView> house = policies.findActive(merchantId, null, at);
                    if (house.isPresent()) {
                        return mergeWithDefaults(house.get(), reasonCode);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("policy lookup failed for merchantId={} reasonCode={}, using default matrix: {}",
                        merchantId, reasonCode, e.toString());
            }
        }
        return DefaultPolicyMatrix.defaultPolicy(merchantId, reasonCode, topReasonCodes(merchantId));
    }

    /** Seeded requirements for a reason code, ignoring merchant overrides. */
    public List<RequirementSpec> requirements(DisputeReasonCode reasonCode) {
        return DefaultPolicyMatrix.requirements(reasonCode);
    }

    /** Requirements a merchant is actually held to for a reason code. */
    public List<RequirementSpec> requirements(String merchantId, DisputeReasonCode reasonCode) {
        return applicablePolicy(merchantId, reasonCode).requirements();
    }

    /**
     * Baseline profile used when readiness is computed with no reason code (contract 7): the union
     * of MANDATORY requirements across the merchant's top reason codes.
     */
    public List<RequirementSpec> baselineRequirements(String merchantId) {
        return DefaultPolicyMatrix.baselineRequirements(topReasonCodes(merchantId));
    }

    /** Constraints handed to the AI service inside {@code InvestigationContext.policyConstraints}. */
    public PolicyConstraints constraints(String merchantId, DisputeReasonCode reasonCode) {
        return applicablePolicy(merchantId, reasonCode).toConstraints();
    }

    /** Safety rule 3 in its simplest form: is this action listed as permitted by the policy? */
    public boolean isActionPermitted(PolicyView policy, RecommendedAction action) {
        return policy != null && policy.permits(action);
    }

    public boolean isActionPermitted(String merchantId, DisputeReasonCode reasonCode, RecommendedAction action) {
        return isActionPermitted(applicablePolicy(merchantId, reasonCode), action);
    }

    /**
     * Full automation check for one proposed action. Beyond the permitted-action list this applies
     * the automation thresholds: confidence floor, contradiction ceiling, readiness floor and the
     * value above which a human always looks.
     */
    public PolicyDecision evaluateAction(PolicyView policy, RecommendedAction action, double confidence,
                                         int contradictionCount, int readinessScore, Money disputeAmount) {
        List<String> reasons = new ArrayList<>();
        if (policy == null) {
            return PolicyDecision.refuse(action, null, List.of("no applicable policy"));
        }
        if (!policy.permits(action)) {
            reasons.add("action " + action + " is not permitted by policy version " + policy.policyVersionId());
        }
        if (action == RecommendedAction.PREPARE_REPRESENTMENT) {
            if (confidence < policy.autoPrepareMinConfidence()) {
                reasons.add("confidence " + confidence + " is below autoPrepareMinConfidence "
                        + policy.autoPrepareMinConfidence());
            }
            if (contradictionCount > policy.maxContradictions()) {
                reasons.add("contradictions " + contradictionCount + " exceed maxContradictions "
                        + policy.maxContradictions());
            }
            if (readinessScore < policy.minReadinessScoreForAutoPrepare()) {
                reasons.add("readiness " + readinessScore + " is below minReadinessScoreForAutoPrepare "
                        + policy.minReadinessScoreForAutoPrepare());
            }
            if (exceedsHumanReviewThreshold(policy, disputeAmount)) {
                reasons.add("dispute amount exceeds humanReviewAboveAmountMinor "
                        + policy.humanReviewAboveAmountMinor() + " " + policy.currency());
            }
        }
        return reasons.isEmpty()
                ? PolicyDecision.permit(action, policy.policyVersionId())
                : PolicyDecision.refuse(action, policy.policyVersionId(), reasons);
    }

    /**
     * True when this dispute is worth more than the policy lets the platform decide unattended.
     * Comparison is in minor units of the same currency; a currency mismatch is treated as
     * "over the threshold" because it cannot be compared safely.
     */
    public boolean exceedsHumanReviewThreshold(PolicyView policy, Money disputeAmount) {
        if (policy == null || disputeAmount == null || policy.humanReviewAboveAmountMinor() <= 0L) {
            return false;
        }
        if (policy.currency() != null && !policy.currency().equalsIgnoreCase(disputeAmount.currency())) {
            return true;
        }
        return disputeAmount.amountMinor() > policy.humanReviewAboveAmountMinor();
    }

    /** Whether an evidence type may appear in a representment package under this policy. */
    public boolean isEvidenceTypePermitted(PolicyView policy, EvidenceType type) {
        return policy == null || !policy.isProhibited(type);
    }

    /**
     * Expiry rule: when evidence of this type, captured at {@code capturedAt}, stops satisfying its
     * requirement. {@code null} when the policy sets no age limit for the type.
     */
    public Instant expiryFor(PolicyView policy, EvidenceType type, Instant capturedAt) {
        if (capturedAt == null) {
            return null;
        }
        Integer maxAgeDays = policy == null
                ? DefaultPolicyMatrix.defaultMaxAgeDays(type)
                : policy.requirementFor(type).map(RequirementSpec::maxAgeDays)
                        .orElseGet(() -> DefaultPolicyMatrix.defaultMaxAgeDays(type));
        if (maxAgeDays == null || maxAgeDays <= 0) {
            return null;
        }
        return capturedAt.plus(maxAgeDays, ChronoUnit.DAYS);
    }

    /** Representment deadline for a dispute opened at {@code openedAt}. */
    public Instant responseDeadline(PolicyView policy, Instant openedAt) {
        int days = policy == null ? DefaultPolicyMatrix.DEFAULT_RESPONSE_WINDOW_DAYS
                : Math.max(1, policy.responseWindowDays());
        return openedAt == null ? null : openedAt.plus(days, ChronoUnit.DAYS);
    }

    public int expiringSoonDays(PolicyView policy) {
        return policy == null ? DefaultPolicyMatrix.DEFAULT_EXPIRING_SOON_DAYS
                : Math.max(1, policy.expiringSoonDays());
    }

    private List<DisputeReasonCode> topReasonCodes(String merchantId) {
        if (policies == null || merchantId == null) {
            return DefaultPolicyMatrix.DEFAULT_TOP_REASON_CODES;
        }
        try {
            List<DisputeReasonCode> codes = policies.topReasonCodes(merchantId, 3);
            return codes == null || codes.isEmpty() ? DefaultPolicyMatrix.DEFAULT_TOP_REASON_CODES : codes;
        } catch (RuntimeException e) {
            log.warn("topReasonCodes lookup failed for merchantId={}: {}", merchantId, e.toString());
            return DefaultPolicyMatrix.DEFAULT_TOP_REASON_CODES;
        }
    }

    /**
     * A merchant house policy carries thresholds but usually no per-reason requirement list; fill the
     * requirements in from the seeded matrix so the readiness bar is still reason-code specific.
     */
    private PolicyView mergeWithDefaults(PolicyView housePolicy, DisputeReasonCode reasonCode) {
        if (!housePolicy.requirements().isEmpty()) {
            return housePolicy;
        }
        return new PolicyView(housePolicy.policyId(), housePolicy.policyVersionId(), housePolicy.version(),
                housePolicy.merchantId(), reasonCode, DefaultPolicyMatrix.requirements(reasonCode),
                housePolicy.permittedActions(), housePolicy.prohibitedEvidenceTypes(),
                housePolicy.autoPrepareMinConfidence(), housePolicy.maxContradictions(),
                housePolicy.minReadinessScoreForAutoPrepare(), housePolicy.humanReviewAboveAmountMinor(),
                housePolicy.currency(), housePolicy.autoSubmitEnabled(), housePolicy.responseWindowDays(),
                housePolicy.expiringSoonDays(), housePolicy.createdBy(), housePolicy.checksum(),
                housePolicy.effectiveFrom(), housePolicy.effectiveTo(), false);
    }
}
