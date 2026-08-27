package com.laserpay.pdei.core.evidence;

import java.time.Instant;

/**
 * Request to supersede an existing evidence artifact with a newer one.
 *
 * <p>There is deliberately no "replace content" operation anywhere in this module: a correction is
 * always a new version that supersedes its parent, never an overwrite. That is what keeps historical
 * reconstruction possible (reference document section 12).</p>
 */
public record NewVersionCommand(
        String parentEvidenceId,
        String filename,
        String contentType,
        byte[] content,
        String summary,
        String sourceEventId,
        String correlationId,
        Instant observedAt,
        Instant expiresAt,
        double qualityScore,
        boolean provenanceVerified,
        String reason,
        String actor) {

    public static NewVersionCommand of(String parentEvidenceId, String filename, byte[] content,
                                       String sourceEventId, String reason) {
        return new NewVersionCommand(parentEvidenceId, filename, null, content, null, sourceEventId, null,
                null, null, 1.0d, sourceEventId != null && !sourceEventId.isBlank(), reason, "SYSTEM");
    }
}
