package com.laserpay.pdei.audit.retention;

import java.time.Instant;
import java.util.List;

/**
 * What a retention evaluation found, and what it would have done.
 *
 * <p>Reporting is the whole output in the default configuration: nothing is deleted, so this record
 * is the deliverable rather than a summary of a side effect.
 *
 * @param dryRun    true when no deletion was attempted, which is the default and the recommended
 *                  permanent state
 * @param destroyed entries actually removed - zero unless someone deliberately enabled destruction
 */
public record RetentionReport(
        Instant evaluatedAt,
        Instant cutoff,
        int retainDays,
        boolean dryRun,
        long totalEntries,
        long eligibleEntries,
        long destroyed,
        List<ChainSummary> chains) {

    public RetentionReport {
        chains = chains == null ? List.of() : List.copyOf(chains);
    }

    /**
     * One merchant chain.
     *
     * @param eligible entries older than the cutoff. Removing them would break verification of
     *                 every entry after them, so this is a number to archive against, not to prune
     *                 against.
     */
    public record ChainSummary(
            String merchantId,
            long entries,
            long eligible,
            Instant oldest,
            Instant newest) {
    }

    public boolean hasEligibleEntries() {
        return eligibleEntries > 0;
    }
}
