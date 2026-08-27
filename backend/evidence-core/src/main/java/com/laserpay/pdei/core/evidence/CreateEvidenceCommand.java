package com.laserpay.pdei.core.evidence;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceType;

import java.time.Instant;

/**
 * Request to register a new evidence artifact.
 *
 * @param content        the bytes themselves; the service hashes them, it never trusts a caller hash
 * @param sourceEventId  the canonical event this artifact came from - the root of its provenance
 * @param observedAt     when the source system observed the fact (may be earlier than now)
 * @param relatedEntityId the payment/order/shipment/delivery/refund this artifact documents
 * @param expiresAt      explicit expiry; when null the policy expiry rule is applied
 */
public record CreateEvidenceCommand(
        String merchantId,
        String transactionId,
        EvidenceType type,
        EvidenceSource source,
        String filename,
        String contentType,
        byte[] content,
        String summary,
        String sourceEventId,
        String correlationId,
        String relatedEntityId,
        Instant observedAt,
        Instant expiresAt,
        double qualityScore,
        boolean provenanceVerified,
        String actor) {

    public static CreateEvidenceCommand of(String merchantId, String transactionId, EvidenceType type,
                                           EvidenceSource source, String filename, byte[] content,
                                           String sourceEventId) {
        return new CreateEvidenceCommand(merchantId, transactionId, type, source, filename, null, content,
                null, sourceEventId, null, null, null, null, 1.0d,
                sourceEventId != null && !sourceEventId.isBlank(), "SYSTEM");
    }

    public CreateEvidenceCommand withSummary(String value) {
        return new CreateEvidenceCommand(merchantId, transactionId, type, source, filename, contentType,
                content, value, sourceEventId, correlationId, relatedEntityId, observedAt, expiresAt,
                qualityScore, provenanceVerified, actor);
    }

    public CreateEvidenceCommand withRelatedEntityId(String value) {
        return new CreateEvidenceCommand(merchantId, transactionId, type, source, filename, contentType,
                content, summary, sourceEventId, correlationId, value, observedAt, expiresAt,
                qualityScore, provenanceVerified, actor);
    }

    public CreateEvidenceCommand withActor(String value) {
        return new CreateEvidenceCommand(merchantId, transactionId, type, source, filename, contentType,
                content, summary, sourceEventId, correlationId, relatedEntityId, observedAt, expiresAt,
                qualityScore, provenanceVerified, value);
    }
}
