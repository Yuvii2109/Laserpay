package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable read view of a single evidence artifact.
 *
 * <p>The subset {@code evidenceId, type, status, sha256, createdAt, summary, version} is the exact
 * shape embedded in {@link InvestigationContext#evidence()} (platform contract 9.1); the remaining
 * fields are additive and are ignored by the AI service.</p>
 */
public record EvidenceView(
        String evidenceId,
        String merchantId,
        String transactionId,
        EvidenceType type,
        EvidenceStatus status,
        EvidenceSource source,
        String objectKey,
        String sha256,
        int version,
        String filename,
        String contentType,
        long sizeBytes,
        String summary,
        String sourceEventId,
        String parentEvidenceId,
        String relatedEntityId,
        double qualityScore,
        boolean provenanceVerified,
        Instant createdAt,
        Instant observedAt,
        Instant expiresAt) {

    /** Statuses that let an evidence artifact satisfy a requirement. */
    public static final Set<EvidenceStatus> USABLE =
            EnumSet.of(EvidenceStatus.ACTIVE, EvidenceStatus.EXPIRING);

    /** True when this artifact currently counts towards readiness. */
    public boolean isUsable() {
        return status != null && USABLE.contains(status);
    }

    /** True when the artifact is expired either by status or by wall clock (late status events). */
    public boolean isExpiredAt(Instant now) {
        if (status == EvidenceStatus.EXPIRED) {
            return true;
        }
        return expiresAt != null && now != null && !expiresAt.isAfter(now);
    }

    /** True when the recorded provenance is sufficient to prove where this artifact came from. */
    public boolean hasVerifiableProvenance() {
        return provenanceVerified
                && sourceEventId != null && !sourceEventId.isBlank()
                && sha256 != null && !sha256.isBlank()
                && source != null;
    }

    public boolean isSuperseded() {
        return status == EvidenceStatus.SUPERSEDED;
    }
}
