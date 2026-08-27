package com.laserpay.pdei.common.domain;

/**
 * What an investigation recommends doing next (PLATFORM-CONTRACT section 6).
 *
 * <p>A recommendation is never self-executing. The policy engine decides whether the action is
 * permitted at all, and {@link #PREPARE_REPRESENTMENT} additionally requires the confidence and
 * contradiction thresholds in PLATFORM-CONTRACT section 9.3 to hold. AI proposes; policy disposes.
 */
public enum RecommendedAction {
    PREPARE_REPRESENTMENT,
    GATHER_MORE_EVIDENCE,
    ACCEPT_LIABILITY,
    ESCALATE_TO_HUMAN,
    REQUEST_POLICY_REVIEW;

    /** Actions that move money or file a representment need the strictest gating. */
    public boolean isFinanciallyMaterial() {
        return this == PREPARE_REPRESENTMENT || this == ACCEPT_LIABILITY;
    }
}
