package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.SafetyDecision;

import java.util.List;

/**
 * Outcome of the deterministic gate that sits between the AI service and any state change.
 * Produced by {@code core.safety.AiResultValidator} and {@code core.safety.SafetyGate}.
 */
public record SafetyVerdict(
        SafetyDecision decision,
        List<String> reasons,
        List<String> unsupportedClaims) {

    public SafetyVerdict {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
    }

    public static SafetyVerdict allow() {
        return new SafetyVerdict(SafetyDecision.ALLOW, List.of(), List.of());
    }

    public static SafetyVerdict allowWithReview(List<String> reasons) {
        return new SafetyVerdict(SafetyDecision.ALLOW_WITH_REVIEW, reasons, List.of());
    }

    public static SafetyVerdict deny(List<String> reasons, List<String> unsupportedClaims) {
        return new SafetyVerdict(SafetyDecision.DENY, reasons, unsupportedClaims);
    }

    public boolean isAllowed() {
        return decision == SafetyDecision.ALLOW;
    }

    public boolean requiresHuman() {
        return decision == SafetyDecision.ALLOW_WITH_REVIEW || decision == SafetyDecision.DENY;
    }

    public boolean isDenied() {
        return decision == SafetyDecision.DENY;
    }
}
