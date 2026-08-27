package com.laserpay.pdei.common.error;

import java.util.Map;

/**
 * The operation conflicts with current state: optimistic-lock version clash, duplicate
 * idempotency key with a different payload, or an illegal state transition
 * (for example approving a case that is already {@code CLOSED}).
 */
public final class ConflictException extends PdeiException {

    public static final String CODE = "CONFLICT";

    public ConflictException(String message) {
        super(CODE, 409, message, null, null);
    }

    public ConflictException(String message, Map<String, Object> details) {
        super(CODE, 409, message, details, null);
    }

    /** Illegal state-machine transition, e.g. {@code CLOSED -> ASSEMBLING}. */
    public static ConflictException illegalTransition(String entityId, Object from, Object to) {
        return new ConflictException(
                "Illegal transition for " + entityId + ": " + from + " -> " + to,
                Map.of("entityId", entityId, "from", String.valueOf(from), "to", String.valueOf(to)));
    }
}
