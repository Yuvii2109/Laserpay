package com.laserpay.pdei.common.error;

import java.util.Map;

/**
 * An event arrived carrying an {@code eventType} that is not in the canonical enum
 * (PLATFORM-CONTRACT section 3.1).
 *
 * <p>Thrown by {@code EventType.fromWire}. Consumers treat this as poison: the record goes to
 * {@code pdei.dlq.v1} rather than blocking the partition, because a future producer version may
 * legitimately emit types this build does not know.
 */
public final class UnknownEventTypeException extends PdeiException {

    public static final String CODE = "UNKNOWN_EVENT_TYPE";

    private final String wireValue;

    public UnknownEventTypeException(String wireValue) {
        super(CODE, 400, "Unknown event type: " + wireValue,
                Map.of("wireValue", String.valueOf(wireValue)), null);
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
