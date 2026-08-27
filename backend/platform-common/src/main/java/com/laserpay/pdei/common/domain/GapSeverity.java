package com.laserpay.pdei.common.domain;

/**
 * How badly a gap hurts (PLATFORM-CONTRACT section 6).
 *
 * <p>{@link #rank()} gives a total order for sorting the at-risk feed and for choosing which gap to
 * show first on a transaction. It is an ordering aid only - it is NOT a readiness penalty; those
 * come from {@link GapType} per PLATFORM-CONTRACT section 7.
 */
public enum GapSeverity {

    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int rank;

    GapSeverity(int rank) {
        this.rank = rank;
    }

    /** 1 (LOW) to 4 (CRITICAL); higher is worse. */
    public int rank() {
        return rank;
    }

    public boolean atLeast(GapSeverity other) {
        return other != null && this.rank >= other.rank;
    }
}
