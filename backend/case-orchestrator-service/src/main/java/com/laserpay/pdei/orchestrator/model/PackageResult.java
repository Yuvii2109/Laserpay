package com.laserpay.pdei.orchestrator.model;

import java.time.Instant;

/**
 * Result of activity 9, {@code prepareRepresentmentPackage}.
 *
 * <p>The bundle itself stays in MinIO ({@code pdei-packages}, PLATFORM-CONTRACT section 11); the
 * workflow carries only its coordinates and its hash. {@code bundleSha256} is what makes the
 * submission provable later: the bytes that were submitted are exactly the bytes that hash to
 * this value.</p>
 */
public record PackageResult(
        String caseId,
        String manifestId,
        int packageVersion,
        String bundleObjectKey,
        String bundleSha256,
        long bundleSizeBytes,
        int itemCount,
        int readinessScore,
        String policyVersionId,
        Instant generatedAt) {
}
