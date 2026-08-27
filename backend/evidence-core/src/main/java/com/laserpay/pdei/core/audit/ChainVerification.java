package com.laserpay.pdei.core.audit;

import java.time.Instant;

/**
 * Result of recomputing a merchant audit chain
 * ({@code GET /api/v1/audit/verify-chain}, {@code GET /audit/v1/chain/verify}).
 *
 * @param intact             true when every recomputed hash matched and every link pointed at its
 *                           predecessor
 * @param eventsChecked      number of entries walked
 * @param firstDivergenceId  audit id of the first entry that failed, or null
 * @param detail             human-readable explanation of the divergence
 */
public record ChainVerification(
        String merchantId,
        boolean intact,
        int eventsChecked,
        String firstDivergenceId,
        String detail,
        Instant verifiedAt) {

    public static ChainVerification intact(String merchantId, int eventsChecked, Instant at) {
        return new ChainVerification(merchantId, true, eventsChecked, null, null, at);
    }

    public static ChainVerification broken(String merchantId, int eventsChecked, String firstDivergenceId,
                                           String detail, Instant at) {
        return new ChainVerification(merchantId, false, eventsChecked, firstDivergenceId, detail, at);
    }
}
