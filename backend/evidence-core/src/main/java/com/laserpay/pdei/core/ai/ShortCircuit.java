package com.laserpay.pdei.core.ai;

/**
 * Why a case did not reach the model.
 *
 * <p>The first three are the deterministic short-circuits mandated by platform contract 9.4: cases
 * whose answer is already known and which must bypass AI entirely. The rest are throttles.</p>
 */
public enum ShortCircuit {
    /** No short-circuit applied; the priority formula decided. */
    NONE,
    /** All MANDATORY requirements satisfied and zero contradictions - auto PREPARE_REPRESENTMENT. */
    ALL_REQUIREMENTS_SATISFIED,
    /** No evidence at all - recommend ACCEPT_LIABILITY to a human. */
    NO_EVIDENCE,
    /** Dispute is already past its deadline - ESCALATE_TO_HUMAN. */
    PAST_DEADLINE,
    /** Priority below the admission threshold. */
    BELOW_PRIORITY_THRESHOLD,
    /** Per-second token bucket exhausted. */
    RATE_LIMITED,
    /** Daily call budget exhausted. */
    BUDGET_EXHAUSTED,
    /** The AI service is unavailable or its circuit breaker is open. */
    PROVIDER_UNAVAILABLE
}
