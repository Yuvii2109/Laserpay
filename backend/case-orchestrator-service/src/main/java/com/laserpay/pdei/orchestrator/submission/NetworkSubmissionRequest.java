package com.laserpay.pdei.orchestrator.submission;

/**
 * Everything a network submitter needs about one representment package.
 *
 * <p>The bytes themselves stay in MinIO; only their coordinates and their hash travel. A real
 * implementation would stream {@code bundleObjectKey} to the PSP and assert the hash on the way
 * out, which is why {@code bundleSha256} is part of the request and not an afterthought.</p>
 */
public record NetworkSubmissionRequest(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        String networkCaseRef,
        int packageVersion,
        String bundleObjectKey,
        String bundleSha256,
        long bundleSizeBytes,
        int itemCount,
        String submittedBy) {
}
