package com.laserpay.pdei.common.id;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Prefixed-id generator backed by an injectable {@link RandomGenerator}.
 *
 * <p>Obtained through {@link Ids#withSeed(long)}. Two generators created with the same seed emit
 * exactly the same id sequence, which is what makes simulator runs reproducible
 * (reference section 39.11: "reproducible workloads via deterministic seeds").
 *
 * <p>Id body alphabet is Crockford base32 minus the ambiguous {@code I L O U}, so ids survive
 * being read aloud, copied out of a log line, or typed into a support ticket.
 *
 * <p>Instances are safe for concurrent use: access to the underlying generator is synchronised.
 */
public final class SeededIdGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int BODY_LENGTH = 8;

    private final RandomGenerator random;

    public SeededIdGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** Deterministic generator for the given seed. */
    public static SeededIdGenerator seeded(long seed) {
        return new SeededIdGenerator(new Random(seed));
    }

    public String merchant() {
        return withPrefix(IdPrefix.MERCHANT);
    }

    public String customer() {
        return withPrefix(IdPrefix.CUSTOMER);
    }

    public String transaction() {
        return withPrefix(IdPrefix.TRANSACTION);
    }

    public String payment() {
        return withPrefix(IdPrefix.PAYMENT);
    }

    public String order() {
        return withPrefix(IdPrefix.ORDER);
    }

    public String shipment() {
        return withPrefix(IdPrefix.SHIPMENT);
    }

    public String delivery() {
        return withPrefix(IdPrefix.DELIVERY);
    }

    public String refund() {
        return withPrefix(IdPrefix.REFUND);
    }

    public String communication() {
        return withPrefix(IdPrefix.COMMUNICATION);
    }

    public String evidence() {
        return withPrefix(IdPrefix.EVIDENCE);
    }

    public String policy() {
        return withPrefix(IdPrefix.POLICY);
    }

    public String dispute() {
        return withPrefix(IdPrefix.DISPUTE);
    }

    public String disputeCase() {
        return withPrefix(IdPrefix.CASE);
    }

    public String investigation() {
        return withPrefix(IdPrefix.INVESTIGATION);
    }

    public String audit() {
        return withPrefix(IdPrefix.AUDIT);
    }

    public String simulation() {
        return withPrefix(IdPrefix.SIMULATION);
    }

    /** {@code prefix} + 8 random base32 characters, e.g. {@code MER-4KQ8ZT1P}. */
    public String withPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        StringBuilder sb = new StringBuilder(prefix.length() + BODY_LENGTH);
        sb.append(prefix);
        long bits = nextLong();
        for (int i = 0; i < BODY_LENGTH; i++) {
            sb.append(ALPHABET[(int) (bits & 0x1FL)]);
            bits >>>= 5;
        }
        return sb.toString();
    }

    /**
     * A RFC-4122 version-4 shaped UUID drawn from this generator. Deterministic for a seeded
     * generator, which lets a replayed simulation produce byte-identical {@code eventId}s.
     */
    public String eventId() {
        long msb;
        long lsb;
        synchronized (random) {
            msb = random.nextLong();
            lsb = random.nextLong();
        }
        msb = (msb & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L; // version 4
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // IETF variant
        return new UUID(msb, lsb).toString();
    }

    private long nextLong() {
        synchronized (random) {
            return random.nextLong();
        }
    }
}
