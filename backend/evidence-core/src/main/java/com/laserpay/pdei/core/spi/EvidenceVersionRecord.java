package com.laserpay.pdei.core.spi;

import java.time.Instant;

/**
 * One immutable row of {@code pdei.evidence_versions}: the append-only ledger of stored objects.
 * A version row is never updated once written - that is the whole point of the table.
 */
public record EvidenceVersionRecord(
        String evidenceVersionId,
        String evidenceId,
        int version,
        String objectKey,
        String sha256,
        long sizeBytes,
        String contentType,
        String filename,
        String sourceEventId,
        String createdBy,
        Instant createdAt) {
}
