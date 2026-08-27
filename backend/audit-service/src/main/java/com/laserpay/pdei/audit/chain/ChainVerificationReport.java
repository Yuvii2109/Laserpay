package com.laserpay.pdei.audit.chain;

import com.laserpay.pdei.core.audit.ChainVerification;

import java.time.Instant;
import java.util.Optional;

/**
 * Result of recomputing one merchant's audit chain, served by
 * {@code GET /audit/v1/chain/verify} and by {@code GET /api/v1/audit/verify-chain}.
 *
 * <p>A verification stops at the first divergence on purpose. Once one link is broken every
 * subsequent hash is expected to mismatch, so listing them all would be thousands of lines of noise
 * describing a single event. The first divergence is the only one that says <em>where</em>.
 *
 * @param eventsChecked entries walked before stopping - with {@code intact}, the length of the
 *                      verified prefix, which is itself useful: "the first 4,812 entries are proven"
 * @param truncated     true when the walk hit the configured maximum rather than the end of the
 *                      chain, so {@code intact} means "intact so far", not "intact"
 */
public record ChainVerificationReport(
        String merchantId,
        boolean intact,
        long eventsChecked,
        long chainLength,
        boolean truncated,
        ChainDivergence divergence,
        Instant verifiedAt) {

    public static ChainVerificationReport intact(String merchantId, long eventsChecked,
                                                 long chainLength, boolean truncated, Instant at) {
        return new ChainVerificationReport(merchantId, true, eventsChecked, chainLength, truncated,
                null, at);
    }

    public static ChainVerificationReport broken(String merchantId, long eventsChecked, long chainLength,
                                                 ChainDivergence divergence, Instant at) {
        return new ChainVerificationReport(merchantId, false, eventsChecked, chainLength, false,
                divergence, at);
    }

    public Optional<ChainDivergence> firstDivergence() {
        return Optional.ofNullable(divergence);
    }

    /**
     * Narrow to {@code evidence-core}'s {@code ChainVerification}, which api-gateway-service and
     * {@code AuditRecorder.verifyChain} already speak. Lossy - it carries one detail string rather
     * than the expected/actual pair - so prefer this record inside the audit service itself.
     */
    public ChainVerification toCoreVerification() {
        if (intact) {
            return ChainVerification.intact(merchantId, (int) Math.min(eventsChecked, Integer.MAX_VALUE),
                    verifiedAt);
        }
        return ChainVerification.broken(merchantId, (int) Math.min(eventsChecked, Integer.MAX_VALUE),
                divergence == null ? null : divergence.auditId(),
                divergence == null ? "chain verification failed" : divergence.detail(),
                verifiedAt);
    }
}
