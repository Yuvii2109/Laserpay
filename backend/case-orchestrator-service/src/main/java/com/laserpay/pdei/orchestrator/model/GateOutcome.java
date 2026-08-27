package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;

import java.util.List;

/**
 * Result of activity 7, {@code validateAndGate} - the deterministic verdict from
 * {@code core.safety.SafetyGate} (PLATFORM-CONTRACT section 9.3).
 *
 * <ul>
 *   <li>{@code ALLOW} - and the action is PREPARE_REPRESENTMENT: the workflow may skip step 8.</li>
 *   <li>{@code ALLOW_WITH_REVIEW} - step 8 runs; a human signs off.</li>
 *   <li>{@code DENY} - step 8 runs and the proposal is presented as rejected; the workflow may
 *       never act on it, whatever the human then decides about the case itself.</li>
 * </ul>
 */
public record GateOutcome(
        String caseId,
        String investigationId,
        SafetyDecision decision,
        RecommendedAction recommendedAction,
        List<String> reasons,
        List<String> unsupportedClaims,
        int readinessScore,
        boolean pastDeadline) {

    public GateOutcome {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
    }

    /** True when the workflow may proceed to step 9 without a human. */
    public boolean autoApproved() {
        return decision == SafetyDecision.ALLOW
                && recommendedAction == RecommendedAction.PREPARE_REPRESENTMENT
                && !pastDeadline;
    }

    public boolean isDenied() {
        return decision == SafetyDecision.DENY;
    }

    public boolean requiresHuman() {
        return decision == SafetyDecision.ALLOW_WITH_REVIEW || decision == SafetyDecision.DENY;
    }
}
