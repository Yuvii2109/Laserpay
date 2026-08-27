package com.laserpay.pdei.common.money;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * The one and only monetary type in PDEI.
 *
 * <p>Non-negotiable rule (PLATFORM-CONTRACT section 5, reference section 39.4): money is always an
 * integer count of <em>minor units</em> plus an ISO-4217 alpha-3 currency code. There is no
 * {@code double}, {@code float} or {@code BigDecimal} anywhere in the financial path. The database
 * mirror of this type is {@code amount_minor BIGINT NOT NULL} + {@code currency CHAR(3) NOT NULL};
 * the TypeScript mirror is {@code { amountMinor: number; currency: string }}.
 *
 * <p>Arithmetic is exact and overflow-checked. Any operation mixing currencies throws
 * {@link CurrencyMismatchException} rather than silently converting: PDEI never performs FX.
 *
 * <p>{@link #toDisplayString()} is the <strong>only</strong> place a decimal point appears, and it
 * is for human display only. Never parse it back.
 */
public record Money(long amountMinor, String currency) implements Comparable<Money> {

    /** Powers of ten for the supported ISO-4217 fraction digit counts (0..4). */
    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L};

    /** Fallback used for currency codes the JVM does not know (test/synthetic codes). */
    private static final int DEFAULT_FRACTION_DIGITS = 2;

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3 || !isAlpha(normalized)) {
            throw new IllegalArgumentException(
                    "currency must be an ISO-4217 alpha-3 code, got: " + currency);
        }
        currency = normalized;
    }

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    /** @throws CurrencyMismatchException when {@code other} carries a different currency. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    /** @throws CurrencyMismatchException when {@code other} carries a different currency. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money multiply(long factor) {
        return new Money(Math.multiplyExact(amountMinor, factor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public boolean isZero() {
        return amountMinor == 0L;
    }

    public boolean isPositive() {
        return amountMinor > 0L;
    }

    public boolean isNegative() {
        return amountMinor < 0L;
    }

    public boolean sameCurrencyAs(Money other) {
        return other != null && currency.equals(other.currency);
    }

    /**
     * Human-readable rendering, e.g. {@code Money.of(1_299_900, "INR").toDisplayString()} yields
     * {@code "INR 12,999.00"}. Fraction digits follow ISO-4217 (JPY 0, INR/USD 2, KWD 3).
     *
     * <p>DISPLAY ONLY. Never persist, hash, compare or transport this string.
     */
    public String toDisplayString() {
        int digits = fractionDigits(currency);
        long unit = POW10[digits];
        boolean negative = amountMinor < 0L;
        long abs = negative ? Math.negateExact(amountMinor) : amountMinor;
        long major = abs / unit;
        long minor = abs % unit;

        StringBuilder sb = new StringBuilder(24);
        sb.append(currency).append(' ');
        if (negative) {
            sb.append('-');
        }
        sb.append(String.format(Locale.ROOT, "%,d", major));
        if (digits > 0) {
            sb.append('.').append(String.format(Locale.ROOT, "%0" + digits + "d", minor));
        }
        return sb.toString();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency;
    }

    /** Number of ISO-4217 minor-unit digits for a currency code; 2 when the JVM does not know it. */
    public static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            if (digits < 0 || digits >= POW10.length) {
                return DEFAULT_FRACTION_DIGITS;
            }
            return digits;
        } catch (IllegalArgumentException | NullPointerException e) {
            return DEFAULT_FRACTION_DIGITS;
        }
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other Money must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static boolean isAlpha(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }
}
