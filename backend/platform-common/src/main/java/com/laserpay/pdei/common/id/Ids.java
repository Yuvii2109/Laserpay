package com.laserpay.pdei.common.id;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Static factory for PDEI identifiers (PLATFORM-CONTRACT section 5).
 *
 * <p>Production code calls the static methods, which draw from a thread-local generator. The
 * simulator calls {@link #withSeed(long)} to obtain a {@link SeededIdGenerator} whose output is
 * fully reproducible for a given seed.
 */
public final class Ids {

    /** Thread-local, non-blocking source for the static (non-reproducible) methods. */
    private static final RandomGenerator DEFAULT_RANDOM = new RandomGenerator() {
        @Override
        public long nextLong() {
            return ThreadLocalRandom.current().nextLong();
        }
    };

    private static final SeededIdGenerator DEFAULT = new SeededIdGenerator(DEFAULT_RANDOM);

    private Ids() {
    }

    public static String merchant() {
        return DEFAULT.merchant();
    }

    public static String customer() {
        return DEFAULT.customer();
    }

    public static String transaction() {
        return DEFAULT.transaction();
    }

    public static String payment() {
        return DEFAULT.payment();
    }

    public static String order() {
        return DEFAULT.order();
    }

    public static String shipment() {
        return DEFAULT.shipment();
    }

    public static String delivery() {
        return DEFAULT.delivery();
    }

    public static String refund() {
        return DEFAULT.refund();
    }

    public static String communication() {
        return DEFAULT.communication();
    }

    public static String evidence() {
        return DEFAULT.evidence();
    }

    public static String policy() {
        return DEFAULT.policy();
    }

    public static String dispute() {
        return DEFAULT.dispute();
    }

    public static String disputeCase() {
        return DEFAULT.disputeCase();
    }

    public static String investigation() {
        return DEFAULT.investigation();
    }

    public static String audit() {
        return DEFAULT.audit();
    }

    public static String simulation() {
        return DEFAULT.simulation();
    }

    /** Random UUID string, used for {@code eventId} and {@code correlationId}. */
    public static String eventId() {
        return UUID.randomUUID().toString();
    }

    public static String withPrefix(String prefix) {
        return DEFAULT.withPrefix(prefix);
    }

    public static boolean hasPrefix(String id, String prefix) {
        return id != null && prefix != null && id.startsWith(prefix);
    }

    /**
     * Reproducible generator for simulation and tests. Same seed produces the same id sequence.
     */
    public static SeededIdGenerator withSeed(long seed) {
        return SeededIdGenerator.seeded(seed);
    }

    /** Generator backed by a caller-supplied source of randomness. */
    public static SeededIdGenerator with(RandomGenerator random) {
        return new SeededIdGenerator(random);
    }
}
