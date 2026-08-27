package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.simulator.world.FailureMix;
import com.laserpay.pdei.simulator.world.FailureProfile;
import com.laserpay.pdei.simulator.world.WorldSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.Locale;

/**
 * Body of {@code POST /sim/v1/runs} (platform contract 8.5):
 * {@code {seed, merchants, transactions, days, disputeRate, failureProfile}}.
 *
 * <p>{@code disputeRate} is accepted in basis points and named {@code disputeRateBps} in the
 * canonical field, with {@code disputeRate} kept as an alias for the contract's spelling. A rate
 * is an integer here for the same reason money is: the generator's reproducibility must not
 * depend on floating-point rounding.
 *
 * <p>{@code startAt} is an extension beyond the contract's six fields, and it matters: the
 * generated world's timestamps are all offsets from it, so pinning it is what makes a run
 * byte-reproducible, and moving it forward is how a demo gets recent-looking data without giving
 * that up.
 *
 * @param seed           reproducibility seed; a fixed default is used when absent, so an omitted
 *                       seed still produces a repeatable world rather than a random one
 * @param merchants      merchants to generate
 * @param transactions   transactions across all merchants
 * @param days           simulated days to spread them over
 * @param disputeRate    dispute rate in basis points (contract spelling)
 * @param disputeRateBps dispute rate in basis points (canonical spelling); wins when both are set
 * @param failureProfile CLEAN | REALISTIC | HOSTILE
 * @param currency       ISO-4217 code, defaults to INR
 * @param startAt        instant the world begins; defaults to {@link WorldSpec#DEFAULT_START_AT}
 * @param reasonCode     forces every generated dispute to one reason code
 * @param requestedBy    actor recorded on the run
 */
public record CreateRunRequestDto(Long seed,
                                  @Min(1) @Max(WorldSpec.MAX_MERCHANTS) Integer merchants,
                                  @Min(1) @Max(WorldSpec.MAX_TRANSACTIONS) Integer transactions,
                                  @Min(1) @Max(WorldSpec.MAX_DAYS) Integer days,
                                  @Min(0) @Max(FailureMix.FULL_BPS) Integer disputeRate,
                                  @Min(0) @Max(FailureMix.FULL_BPS) Integer disputeRateBps,
                                  String failureProfile,
                                  String currency,
                                  Instant startAt,
                                  String reasonCode,
                                  String requestedBy) {

    /** Used when no seed is supplied, so the default run is still reproducible. */
    public static final long DEFAULT_SEED = 4281L;

    /** Builds the generator spec, applying every default. */
    public WorldSpec toSpec() {
        FailureProfile profile = FailureProfile.parse(failureProfile);
        int rate = disputeRateBps != null ? disputeRateBps
                : disputeRate != null ? disputeRate
                : 250;
        return new WorldSpec(
                seed == null ? DEFAULT_SEED : seed,
                merchants == null ? 3 : merchants,
                transactions == null ? 200 : transactions,
                days == null ? 30 : days,
                rate,
                FailureMix.of(profile),
                null,
                currency,
                startAt,
                parseReasonCode(reasonCode),
                0L,
                0);
    }

    public String actor() {
        return requestedBy == null || requestedBy.isBlank() ? "api" : requestedBy.strip();
    }

    private static DisputeReasonCode parseReasonCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DisputeReasonCode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown dispute reason code: " + raw);
        }
    }
}
