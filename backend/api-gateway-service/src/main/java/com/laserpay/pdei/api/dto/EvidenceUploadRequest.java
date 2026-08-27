package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * The JSON {@code metadata} part of {@code POST /evidence} (merchant portal upload).
 *
 * <p>The route is {@code multipart/form-data} with exactly two parts: {@code file} (the bytes) and
 * {@code metadata} (this record, as {@code application/json}). Keeping the metadata as one JSON part
 * rather than a scatter of form fields means it is validated by the same bean-validation rules and
 * parsed by the same mapper as every other request body.</p>
 *
 * <p>Notice what is <em>not</em> here: no sha256 and no object key. The hash is computed by
 * {@code EvidenceService} from the bytes actually written to MinIO, never taken from the uploader.
 * An uploader-supplied hash would make the integrity check circular.</p>
 *
 * @param source        defaults to MERCHANT_PORTAL, the only honest provenance for a portal upload
 * @param sourceEventId the canonical event this document evidences, when the portal knows it
 * @param expiresAt     an explicit expiry; when null the policy expiry table decides
 * @param qualityScore  0.0 to 1.0; below the policy floor it raises a LOW_QUALITY gap
 */
public record EvidenceUploadRequest(
        @NotBlank(message = "merchantId is required")
        @Pattern(regexp = "^MER-[A-Za-z0-9_-]+$", message = "must be a MER- prefixed id")
        String merchantId,

        @NotBlank(message = "transactionId is required")
        @Pattern(regexp = "^TX-[A-Za-z0-9_-]+$", message = "must be a TX- prefixed id")
        String transactionId,

        @NotNull(message = "type is required")
        EvidenceType type,

        EvidenceSource source,

        String summary,

        String relatedEntityId,

        String sourceEventId,

        Instant observedAt,

        Instant expiresAt,

        @DecimalMin(value = "0.0", message = "qualityScore must be between 0.0 and 1.0")
        @DecimalMax(value = "1.0", message = "qualityScore must be between 0.0 and 1.0")
        Double qualityScore,

        String actor) {

    /** The provenance recorded when the uploader does not declare one. */
    public EvidenceSource effectiveSource() {
        return source == null ? EvidenceSource.MERCHANT_PORTAL : source;
    }

    /**
     * A portal upload is trusted less than a system-derived artifact by default: a human attaching a
     * PDF has not proved anything about where it came from.
     */
    public double effectiveQualityScore() {
        return qualityScore == null ? 0.8d : qualityScore;
    }

    public String effectiveActor() {
        return actor == null || actor.isBlank() ? "MERCHANT_PORTAL" : actor;
    }

    /**
     * Provenance counts as verified only when the upload names the canonical event it came from.
     * Without that link the artifact is unverifiable by construction, and the gap detector should
     * say so rather than the API asserting otherwise.
     */
    public boolean provenanceVerified() {
        return sourceEventId != null && !sourceEventId.isBlank();
    }
}
