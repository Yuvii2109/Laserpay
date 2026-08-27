package com.laserpay.pdei.simulator.world;

/**
 * Which side of the admission gate a scenario is expected to land on.
 *
 * <p>This is the load-bearing claim of the whole platform (reference section 5.6: "scale the
 * expensive layer with ambiguity, not with data volume"), so every curated scenario declares
 * which path it should take. A scenario whose expected path stops matching what the system does
 * is a regression in the deterministic engine, not a flaky demo.
 */
public enum AiPath {

    /**
     * Resolved without the model. Either every MANDATORY requirement is satisfied with zero
     * contradictions (auto {@code PREPARE_REPRESENTMENT}), or there is no evidence at all
     * ({@code ACCEPT_LIABILITY}), or the deadline has already passed ({@code ESCALATE_TO_HUMAN}).
     * Platform contract 9.4 calls these the deterministic short-circuits.
     */
    DETERMINISTIC,

    /**
     * Genuinely ambiguous: contradictions, gaps or a low deterministic confidence push the
     * admission priority over the threshold and the case is sent to the reasoning service.
     */
    AMBIGUOUS
}
