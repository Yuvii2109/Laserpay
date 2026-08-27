package com.laserpay.pdei.simulator.world;

import java.util.Locale;

/**
 * Named preset for {@link FailureMix}, accepted as the {@code failureProfile} field of
 * {@code POST /sim/v1/runs} (platform contract 8.5).
 */
public enum FailureProfile {

    /** Perfect data: every transaction fully evidenced, nothing late, nothing contradictory. */
    CLEAN,

    /** The default. Mostly fine, with the gaps a real merchant discovers only under dispute. */
    REALISTIC,

    /** Everything wrong at once; the shape used for stress benchmarks. */
    HOSTILE;

    /** Lenient parse for the REST layer; unknown or blank input falls back to {@link #REALISTIC}. */
    public static FailureProfile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return REALISTIC;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return REALISTIC;
        }
    }
}
