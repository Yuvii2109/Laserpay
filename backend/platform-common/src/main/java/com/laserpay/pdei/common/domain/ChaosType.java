package com.laserpay.pdei.common.domain;

/**
 * Failure modes the simulator can inject (PLATFORM-CONTRACT section 6, reference section 36).
 *
 * <p>These exist to prove the platform's correctness claims rather than assert them: duplicate,
 * delayed and out-of-order events prove consumer idempotency; evidence deletion, corruption and
 * expiry prove the integrity and readiness engines react; worker kills and consumer restarts prove
 * recovery; conflicting evidence proves contradiction detection routes to human review.
 */
public enum ChaosType {

    // --- event-stream faults ---
    DUPLICATE_EVENT(Category.EVENT_STREAM),
    DELAYED_EVENT(Category.EVENT_STREAM),
    OUT_OF_ORDER_EVENT(Category.EVENT_STREAM),
    DROP_EVENT(Category.EVENT_STREAM),
    REPLAY_EVENTS(Category.EVENT_STREAM),

    // --- evidence faults ---
    DELETE_EVIDENCE(Category.EVIDENCE),
    CORRUPT_EVIDENCE_HASH(Category.EVIDENCE),
    EXPIRE_EVIDENCE(Category.EVIDENCE),
    CONFLICTING_EVIDENCE(Category.EVIDENCE),

    // --- infrastructure faults ---
    KILL_WORKER(Category.INFRASTRUCTURE),
    RESTART_CONSUMER(Category.INFRASTRUCTURE),
    SLOW_CONSUMER(Category.INFRASTRUCTURE),

    // --- workload faults ---
    INJECT_DISPUTE(Category.WORKLOAD);

    /** Grouping used by the Simulation and Chaos Console. */
    public enum Category {
        EVENT_STREAM,
        EVIDENCE,
        INFRASTRUCTURE,
        WORKLOAD
    }

    private final Category category;

    ChaosType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
