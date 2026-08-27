package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;

import java.time.Instant;

/**
 * A single reason a transaction is not defensible yet.
 *
 * <p>{@code type}, {@code evidenceType} and {@code severity} are the fields serialised into
 * {@link InvestigationContext#gaps()} (platform contract 9.1).</p>
 */
public record ReadinessGap(
        String gapId,
        String transactionId,
        GapType type,
        EvidenceType evidenceType,
        GapSeverity severity,
        String evidenceId,
        String detail,
        Instant detectedAt,
        Instant expiresAt) {

    public boolean isBlocking() {
        return severity == GapSeverity.HIGH || severity == GapSeverity.CRITICAL;
    }
}
