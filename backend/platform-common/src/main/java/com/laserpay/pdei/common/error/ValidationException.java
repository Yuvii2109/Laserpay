package com.laserpay.pdei.common.error;

import java.util.Map;

/**
 * Input, payload or schema did not satisfy the contract.
 *
 * <p>Non-retryable: Temporal activities that throw this fail the activity immediately
 * (PLATFORM-CONTRACT section 10).
 */
public final class ValidationException extends PdeiException {

    public static final String CODE = "VALIDATION_ERROR";

    public ValidationException(String message) {
        super(CODE, 400, message, null, null);
    }

    public ValidationException(String message, Throwable cause) {
        super(CODE, 400, message, null, cause);
    }

    public ValidationException(String message, Map<String, Object> details) {
        super(CODE, 400, message, details, null);
    }

    public ValidationException(String message, Map<String, Object> details, Throwable cause) {
        super(CODE, 400, message, details, cause);
    }

    /** Convenience for field-level failures: {@code field=amountMinor}. */
    public static ValidationException field(String field, String problem) {
        return new ValidationException(field + ": " + problem, Map.of("field", field));
    }
}
