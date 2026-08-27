package com.laserpay.pdei.audit.controller;

import com.laserpay.pdei.audit.chain.ChainDivergence;
import com.laserpay.pdei.audit.chain.ChainVerificationReport;

import java.time.Instant;
import java.util.List;

/**
 * Response of {@code GET /audit/v1/chain/verify}.
 *
 * <p>Reports one chain, or a sweep across every chain when no merchant is given. The
 * {@code divergence} block is present only when something failed, and it names the entry, the
 * position, and both hashes - what verification expected and what the row actually holds. Anything
 * less would tell an operator that history was altered without telling them where.
 *
 * @param intact        true when every chain walked verified end to end
 * @param chainsChecked how many merchant chains were walked
 * @param truncated     true when a walk stopped at the configured maximum rather than at the end of
 *                      the chain: {@code intact} then means "intact so far"
 */
public record ChainVerifyResponse(
        boolean intact,
        int chainsChecked,
        long eventsChecked,
        boolean truncated,
        List<ChainReport> chains,
        Instant verifiedAt) {

    public ChainVerifyResponse {
        chains = chains == null ? List.of() : List.copyOf(chains);
    }

    /** One merchant chain's result. */
    public record ChainReport(
            String merchantId,
            boolean intact,
            long eventsChecked,
            long chainLength,
            boolean truncated,
            Divergence divergence) {

        static ChainReport from(ChainVerificationReport report) {
            return new ChainReport(report.merchantId(), report.intact(), report.eventsChecked(),
                    report.chainLength(), report.truncated(),
                    report.firstDivergence().map(Divergence::from).orElse(null));
        }
    }

    /** The first entry that failed to verify. */
    public record Divergence(
            String auditId,
            int index,
            long sequenceNo,
            String kind,
            String expectedHash,
            String actualHash,
            String detail,
            Instant entryOccurredAt) {

        static Divergence from(ChainDivergence divergence) {
            return new Divergence(divergence.auditId(), divergence.index(), divergence.sequenceNo(),
                    divergence.kind().name(), divergence.expectedHash(), divergence.actualHash(),
                    divergence.detail(), divergence.entryOccurredAt());
        }
    }

    public static ChainVerifyResponse of(List<ChainVerificationReport> reports, Instant verifiedAt) {
        boolean intact = reports.stream().allMatch(ChainVerificationReport::intact);
        long events = reports.stream().mapToLong(ChainVerificationReport::eventsChecked).sum();
        boolean truncated = reports.stream().anyMatch(ChainVerificationReport::truncated);
        return new ChainVerifyResponse(intact, reports.size(), events, truncated,
                reports.stream().map(ChainReport::from).toList(), verifiedAt);
    }
}
