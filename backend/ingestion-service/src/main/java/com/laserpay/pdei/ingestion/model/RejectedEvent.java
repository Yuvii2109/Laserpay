package com.laserpay.pdei.ingestion.model;

import java.util.List;

/**
 * One entry of the {@code rejected[]} array in the {@code 202 Accepted} response
 * (PLATFORM-CONTRACT section 8.2).
 *
 * <p>A rejection is per-event, not per-request: a batch of 100 where three events fail schema
 * validation still returns 202 with {@code accepted: 97} and three entries here. The index makes
 * the entry addressable back to the caller's array position.
 *
 * @param index           position in the submitted array (0 for the single-event endpoint)
 * @param rawEventId      the submitted or derived id, when one could be determined
 * @param sourceSystem    echoed back so a multi-source batch is diagnosable
 * @param sourceEventType echoed back for the same reason
 * @param code            machine-readable reason: {@code SCHEMA_VALIDATION_FAILED},
 *                        {@code UNKNOWN_SCHEMA}, {@code PUBLISH_FAILED}, {@code MALFORMED_REQUEST}
 * @param message         one-line summary
 * @param errors          field-level detail; empty when the failure is not field-scoped
 */
public record RejectedEvent(int index,
                            String rawEventId,
                            String sourceSystem,
                            String sourceEventType,
                            String code,
                            String message,
                            List<FieldError> errors) {

    /** Machine-readable rejection codes. Stable strings: adapters branch on them. */
    public static final String SCHEMA_VALIDATION_FAILED = "SCHEMA_VALIDATION_FAILED";
    public static final String UNKNOWN_SCHEMA = "UNKNOWN_SCHEMA";
    public static final String PUBLISH_FAILED = "PUBLISH_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

    public RejectedEvent {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
