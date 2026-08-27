package com.laserpay.pdei.common.domain;

/**
 * Outcome of the safety gate (PLATFORM-CONTRACT sections 6 and 9.3).
 *
 * <p>Every AI-influenced outcome passes through this enum before it can affect a case:
 * <ul>
 *   <li>{@link #ALLOW} - validated against the record and the policy; automation may proceed.</li>
 *   <li>{@link #ALLOW_WITH_REVIEW} - permitted, but a human must confirm before submission.</li>
 *   <li>{@link #DENY} - rejected; the case routes to {@code AWAITING_HUMAN_REVIEW} and the
 *       rejection is audited, incrementing {@code pdei_ai_unsupported_claims_total}.</li>
 * </ul>
 */
public enum SafetyDecision {
    ALLOW,
    ALLOW_WITH_REVIEW,
    DENY;

    /** Whether the platform may act without a human signal. */
    public boolean permitsAutomation() {
        return this == ALLOW;
    }
}
