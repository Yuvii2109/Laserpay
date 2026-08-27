package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.util.List;

/**
 * Manifest of a representment package. Serialised to
 * {@code pdei-packages/{merchantId}/{caseId}/manifest.json} (platform contract 11) alongside the
 * bundle zip, and returned by {@code GET /api/v1/cases/{caseId}/package}.
 *
 * <p>The manifest is the auditable statement of exactly which bytes were submitted: every item
 * carries the sha256 that was verified at assembly time.</p>
 */
public record PackageManifest(
        String manifestId,
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        DisputeReasonCode reasonCode,
        Money disputeAmount,
        int packageVersion,
        String bundleObjectKey,
        String bundleSha256,
        long bundleSizeBytes,
        List<Item> items,
        String narrative,
        String policyVersionId,
        int readinessScore,
        ReadinessBand readinessBand,
        String generatedBy,
        Instant generatedAt) {

    public PackageManifest {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** One file inside the bundle. {@code entryPath} is its path within the zip. */
    public record Item(
            String evidenceId,
            EvidenceType type,
            RequirementStrength strength,
            int version,
            String sha256,
            String objectKey,
            String filename,
            String contentType,
            long sizeBytes,
            String entryPath,
            Instant capturedAt) {
    }
}
