package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.RecommendedAction;

/**
 * Result of activity 5, {@code runAdmissionControl}: the flattened
 * {@code core.ai.AdmissionDecision} from PLATFORM-CONTRACT section 9.4.
 *
 * <p>When {@code admit} is false and {@code deterministicAction} is set, one of the three mandatory
 * short-circuits fired and the model must be bypassed entirely. When {@code admit} is false and
 * {@code deterministicAction} is null, a throttle fired (priority below threshold, rate limit,
 * daily budget) - the case still gets an answer, just a deterministic one.</p>
 */
public record AdmissionOutcome(
        String caseId,
        boolean admit,
        int priority,
        String reason,
        String shortCircuit,
        RecommendedAction deterministicAction,
        double financialImpact,
        double deadlineUrgency,
        double ambiguityScore,
        double deterministicConfidence) {

    /** True when the deterministic path already resolved the case and AI adds nothing. */
    public boolean resolvedDeterministically() {
        return deterministicAction != null;
    }
}
