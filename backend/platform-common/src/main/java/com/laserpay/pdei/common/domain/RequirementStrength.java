package com.laserpay.pdei.common.domain;

/**
 * How strongly a policy requires (or forbids) an evidence type
 * (PLATFORM-CONTRACT sections 6 and 7).
 *
 * <p>{@link #weight()} feeds the readiness formula directly:
 * <pre>
 *   base = 100 * (SUM weight(satisfied mandatory) + 0.5 * SUM weight(satisfied recommended))
 *              / (SUM weight(all mandatory)       + 0.5 * SUM weight(all recommended))
 * </pre>
 *
 * <p>{@link #PROHIBITED} carries weight 0 and never contributes to the score: its role is to make
 * the safety gate reject a package (or an AI recommendation) that includes such evidence at all.
 */
public enum RequirementStrength {

    MANDATORY(3),
    RECOMMENDED(2),
    OPTIONAL(1),
    PROHIBITED(0);

    private final int weight;

    RequirementStrength(int weight) {
        this.weight = weight;
    }

    /** Scoring weight: MANDATORY 3, RECOMMENDED 2, OPTIONAL 1, PROHIBITED 0. */
    public int weight() {
        return weight;
    }

    /** Whether an unsatisfied requirement of this strength blocks automated preparation. */
    public boolean isBlocking() {
        return this == MANDATORY;
    }
}
