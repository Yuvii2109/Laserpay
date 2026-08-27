package com.laserpay.pdei.common.money;

/**
 * Thrown when two {@link Money} values with different currencies are combined or compared.
 *
 * <p>PDEI never performs implicit FX conversion: mixing currencies is always a programming or data
 * error, so this is an unchecked failure rather than a recoverable condition. It deliberately does
 * NOT extend {@code PdeiException} - the money package has no dependency on the error package.
 */
public final class CurrencyMismatchException extends RuntimeException {

    private final String left;
    private final String right;

    public CurrencyMismatchException(String left, String right) {
        super("Currency mismatch: " + left + " vs " + right);
        this.left = left;
        this.right = right;
    }

    public String left() {
        return left;
    }

    public String right() {
        return right;
    }
}
