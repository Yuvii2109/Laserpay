package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.domain.DisputeReasonCode;

import java.time.Instant;
import java.util.Locale;

/**
 * Everything {@link WorldGenerator} needs. {@code (seed, spec)} fully determines the output.
 *
 * <p><strong>Why {@code startAt} is part of the spec.</strong> A generator that called
 * {@code Instant.now()} would produce a different world every second and the reproducibility
 * claim would be a lie. Every timestamp in a generated world is an offset from {@code startAt},
 * so a run is byte-identical for a fixed {@code (seed, startAt)} pair. When the field is omitted
 * it defaults to {@link #DEFAULT_START_AT} rather than to the wall clock - deliberately, so the
 * default behaviour is the reproducible one and "recent-looking data" is what you have to ask
 * for.
 *
 * @param seed                 the reproducibility seed
 * @param merchants            number of merchants to generate
 * @param transactions         total transactions across all merchants
 * @param days                 span of simulated time the transactions are spread over
 * @param disputeRateBps       basis points of transactions that end in a dispute (250 = 2.50%)
 * @param failureMix           how broken the generated data is
 * @param scenarioKey          curated scenario this spec came from, null for an ad-hoc run
 * @param currency             ISO-4217 code for every amount in the world
 * @param startAt              instant the simulated world begins
 * @param forcedReasonCode     when set, every generated dispute uses this reason code
 * @param minAmountMinor       floor for a transaction's total, in minor units; 0 for no floor.
 *                             Used by the high-value scenario, where the point is the admission
 *                             priority a large financial impact produces.
 * @param disputeDeadlineDays  fixed representment deadline in days after the dispute opens;
 *                             0 draws the usual 7-21 day spread
 */
public record WorldSpec(long seed,
                        int merchants,
                        int transactions,
                        int days,
                        int disputeRateBps,
                        FailureMix failureMix,
                        String scenarioKey,
                        String currency,
                        Instant startAt,
                        DisputeReasonCode forcedReasonCode,
                        long minAmountMinor,
                        int disputeDeadlineDays) {

    /** Default world start. A fixed instant, not "now": see the class javadoc. */
    public static final Instant DEFAULT_START_AT = Instant.parse("2026-01-05T06:00:00Z");

    public static final String DEFAULT_CURRENCY = "INR";
    public static final int MAX_MERCHANTS = 500;
    public static final int MAX_TRANSACTIONS = 200_000;
    public static final int MAX_DAYS = 720;

    public WorldSpec {
        merchants = clamp(merchants, 1, MAX_MERCHANTS);
        transactions = clamp(transactions, 1, MAX_TRANSACTIONS);
        days = clamp(days, 1, MAX_DAYS);
        disputeRateBps = clamp(disputeRateBps, 0, FailureMix.FULL_BPS);
        failureMix = failureMix == null ? FailureMix.realistic() : failureMix;
        currency = (currency == null || currency.isBlank())
                ? DEFAULT_CURRENCY
                : currency.strip().toUpperCase(Locale.ROOT);
        startAt = startAt == null ? DEFAULT_START_AT : startAt;
        minAmountMinor = Math.max(0L, minAmountMinor);
        disputeDeadlineDays = clamp(disputeDeadlineDays, 0, 365);
    }

    /** A small, realistic world - the default shape of {@code POST /sim/v1/runs}. */
    public static WorldSpec defaults(long seed) {
        return new WorldSpec(seed, 3, 200, 30, 250, FailureMix.realistic(), null,
                DEFAULT_CURRENCY, DEFAULT_START_AT, null, 0L, 0);
    }

    /** Single-merchant world where every transaction is disputed - the scenario shape. */
    public static WorldSpec scenario(long seed, String scenarioKey, int transactions, int days,
                                     FailureMix mix, DisputeReasonCode reasonCode) {
        return new WorldSpec(seed, 1, transactions, days, FailureMix.FULL_BPS, mix, scenarioKey,
                DEFAULT_CURRENCY, DEFAULT_START_AT, reasonCode, 0L, 0);
    }

    /** End of the simulated window. */
    public Instant endAt() {
        return startAt.plusSeconds((long) days * 86_400L);
    }

    /** Copy with a different seed, keeping every other parameter - used by scenario reruns. */
    public WorldSpec withSeed(long newSeed) {
        return new WorldSpec(newSeed, merchants, transactions, days, disputeRateBps, failureMix,
                scenarioKey, currency, startAt, forcedReasonCode, minAmountMinor, disputeDeadlineDays);
    }

    /**
     * Copy with a different world start. Determinism is preserved for any fixed value, so this is
     * how a demo gets recent-looking timestamps without giving up reproducibility.
     */
    public WorldSpec withStartAt(Instant newStartAt) {
        return new WorldSpec(seed, merchants, transactions, days, disputeRateBps, failureMix,
                scenarioKey, currency, newStartAt, forcedReasonCode, minAmountMinor, disputeDeadlineDays);
    }

    public WorldSpec withMinAmountMinor(long minor) {
        return new WorldSpec(seed, merchants, transactions, days, disputeRateBps, failureMix,
                scenarioKey, currency, startAt, forcedReasonCode, minor, disputeDeadlineDays);
    }

    public WorldSpec withDisputeDeadlineDays(int deadlineDays) {
        return new WorldSpec(seed, merchants, transactions, days, disputeRateBps, failureMix,
                scenarioKey, currency, startAt, forcedReasonCode, minAmountMinor, deadlineDays);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
