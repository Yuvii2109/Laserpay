package com.laserpay.pdei.core.spi;

import java.time.Instant;

/**
 * One row of {@code pdei.evidence_relationships}. {@code relation} uses the constants on
 * {@code com.laserpay.pdei.core.model.EvidenceEdge} so the graph and the table agree.
 */
public record EvidenceRelationship(
        String relationshipId,
        String fromEvidenceId,
        String toEvidenceId,
        String relation,
        String detail,
        Instant createdAt) {
}
