package com.laserpay.pdei.normalization.adapter;

/**
 * The raw event cannot be turned into a {@link com.laserpay.pdei.common.event.CanonicalEvent}:
 * no adapter claims the source system, the adapter does not recognise the source event type, or a
 * field the canonical envelope requires is absent.
 *
 * <p>Non-retryable: the same bytes will fail the same way on every attempt. The listener routes the
 * record to {@code pdei.dlq.v1} as a {@code DeadLetterEnvelope} carrying the original payload, so
 * the adapter can be extended and the event replayed. Nothing is ever silently dropped
 * (docs/event-catalog.md section 11).
 */
public class UnmappableEventException extends RuntimeException {

    private final String sourceSystem;
    private final String sourceEventType;

    public UnmappableEventException(String sourceSystem, String sourceEventType, String reason) {
        super("cannot normalize sourceSystem=" + sourceSystem + " sourceEventType=" + sourceEventType
                + ": " + reason);
        this.sourceSystem = sourceSystem;
        this.sourceEventType = sourceEventType;
    }

    public UnmappableEventException(String sourceSystem, String sourceEventType, String reason,
                                    Throwable cause) {
        super("cannot normalize sourceSystem=" + sourceSystem + " sourceEventType=" + sourceEventType
                + ": " + reason, cause);
        this.sourceSystem = sourceSystem;
        this.sourceEventType = sourceEventType;
    }

    public String sourceSystem() {
        return sourceSystem;
    }

    public String sourceEventType() {
        return sourceEventType;
    }
}
