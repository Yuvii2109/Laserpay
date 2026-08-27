package com.laserpay.pdei.common.error;

import java.util.Map;

/**
 * A requested action is not permitted by the applicable merchant policy.
 *
 * <p>Thrown by the policy engine and by the AI safety gate. This is the mechanism behind
 * "AI proposes; policy disposes" (reference section 5.2): a model recommendation that the policy
 * forbids surfaces as this failure, is audited, and routes the case to human review.
 * Non-retryable in Temporal.
 */
public final class PolicyViolationException extends PdeiException {

    public static final String CODE = "POLICY_VIOLATION";

    public PolicyViolationException(String message) {
        super(CODE, 422, message, null, null);
    }

    public PolicyViolationException(String message, Map<String, Object> details) {
        super(CODE, 422, message, details, null);
    }

    public PolicyViolationException(String message, Map<String, Object> details, Throwable cause) {
        super(CODE, 422, message, details, cause);
    }
}
