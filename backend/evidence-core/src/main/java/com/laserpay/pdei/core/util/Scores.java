package com.laserpay.pdei.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding and clamping helpers for scores.
 *
 * <p>Only ever applied to dimensionless scores (readiness 0-100, admission priority 0-100,
 * confidence 0-1). Money never passes through here: monetary values are {@code long} minor units
 * end to end.</p>
 */
public final class Scores {

    private Scores() {
    }

    /** Round half up, exactly as required by platform contract 7. */
    public static int roundHalfUp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** {@code roundHalfUp} then clamp - the exact tail of the readiness formula. */
    public static int roundAndClamp(double value, int min, int max) {
        return clamp(roundHalfUp(value), min, max);
    }
}
