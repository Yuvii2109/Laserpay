package com.laserpay.pdei.common.domain;

/**
 * Coarse readiness classification derived from the deterministic readiness score
 * (PLATFORM-CONTRACT sections 6 and 7).
 *
 * <pre>
 *   READY         score &gt;= 90
 *   NEARLY_READY  75..89
 *   AT_RISK       50..74
 *   NOT_READY     &lt; 50
 * </pre>
 *
 * <p>The band, not the raw number, is what the control tower filters and colours by, and what the
 * at-risk feed keys off. {@link #fromScore(int)} is the single definition of the boundaries: no
 * other module may re-derive them.
 */
public enum ReadinessBand {

    READY(90, 100),
    NEARLY_READY(75, 89),
    AT_RISK(50, 74),
    NOT_READY(0, 49);

    private final int minScore;
    private final int maxScore;

    ReadinessBand(int minScore, int maxScore) {
        this.minScore = minScore;
        this.maxScore = maxScore;
    }

    /** Inclusive lower bound of the band. */
    public int minScore() {
        return minScore;
    }

    /** Inclusive upper bound of the band. */
    public int maxScore() {
        return maxScore;
    }

    /**
     * Band for a score. Scores are expected in 0..100 (the readiness engine clamps them); values
     * outside that range are still classified sensibly rather than throwing, so a scoring bug can
     * never take a dashboard down.
     */
    public static ReadinessBand fromScore(int score) {
        if (score >= READY.minScore) {
            return READY;
        }
        if (score >= NEARLY_READY.minScore) {
            return NEARLY_READY;
        }
        if (score >= AT_RISK.minScore) {
            return AT_RISK;
        }
        return NOT_READY;
    }

    /** True when the band is one the at-risk feed should surface to the merchant. */
    public boolean needsAttention() {
        return this == AT_RISK || this == NOT_READY;
    }
}
