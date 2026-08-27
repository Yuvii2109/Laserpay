package com.laserpay.pdei.readiness.sweep;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.ReadinessBand;

import java.time.Instant;

/**
 * One row of the at-risk feed: a transaction that would not defend itself today.
 *
 * <p>This is the shape {@code GET /api/v1/gaps} serves and the Merchant Control Tower renders as
 * its at-risk list. It is a join of the current readiness snapshot with the worst unresolved gap on
 * that transaction, materialised by {@link AtRiskScanner} so the read path never has to compute it.
 *
 * @param worstGapType  type of the highest-severity unresolved gap, or null when the transaction is
 *                      at risk with no gap rows at all (nothing has been attached yet)
 * @param worstSeverity severity of that gap, or null for the same reason
 * @param openGapCount  unresolved gaps on this transaction
 */
public record AtRiskEntry(
        String transactionId,
        String merchantId,
        int score,
        ReadinessBand band,
        GapType worstGapType,
        GapSeverity worstSeverity,
        int openGapCount,
        Instant computedAt) {

    /** True when a human should look at this now rather than at the end of the week. */
    public boolean isUrgent() {
        return worstSeverity == GapSeverity.CRITICAL
                || band == ReadinessBand.NOT_READY
                || (worstSeverity == GapSeverity.HIGH && band == ReadinessBand.AT_RISK);
    }

    /** True when the snapshot behind this row is older than {@code staleBefore}. */
    public boolean isStale(Instant staleBefore) {
        return computedAt == null || (staleBefore != null && computedAt.isBefore(staleBefore));
    }
}
