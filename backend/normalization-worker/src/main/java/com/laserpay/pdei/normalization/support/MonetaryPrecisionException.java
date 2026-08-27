package com.laserpay.pdei.normalization.support;

/**
 * A source system sent a monetary value that cannot be converted to minor units without inventing
 * precision - a JSON floating-point literal, or more fraction digits than the currency allows.
 *
 * <p>Non-retryable by construction: retrying will not change the bytes. The record is dead-lettered
 * with its original payload so the producer can be fixed and the event replayed from
 * {@code pdei.raw.events.v1}.
 */
public class MonetaryPrecisionException extends RuntimeException {

    public MonetaryPrecisionException(String message) {
        super(message);
    }

    public MonetaryPrecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
