package com.laserpay.pdei.core.ai;

import com.laserpay.pdei.common.domain.RecommendedAction;

/**
 * Outcome of admission control.
 *
 * @param admit                 whether the case is sent to the model
 * @param priority              0-100 priority score from the contract 9.4 formula
 * @param shortCircuit          why the model was bypassed, if it was
 * @param deterministicAction   the action the deterministic path already resolved, if any
 * @param financialImpact       normalised financial impact term, in [0,1]
 * @param deadlineUrgency       deadline urgency term, in [0,1]
 * @param ambiguityScore        ambiguity term, in [0,1]
 * @param deterministicConfidence confidence of the deterministic path, in [0,1]
 */
public record AdmissionDecision(
        boolean admit,
        int priority,
        String reason,
        ShortCircuit shortCircuit,
        RecommendedAction deterministicAction,
        double financialImpact,
        double deadlineUrgency,
        double ambiguityScore,
        double deterministicConfidence) {

    public boolean resolvedDeterministically() {
        return deterministicAction != null;
    }
}
