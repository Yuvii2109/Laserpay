package com.laserpay.pdei.orchestrator.model;

import java.time.Instant;

/**
 * Result of activity 10, {@code submitRepresentment}.
 *
 * <p>{@code simulated} is true for every receipt this baseline produces: submission goes through
 * {@code submission.SimulatedNetworkSubmitter}, which is a deterministic stand-in for a real PSP
 * or card-network API. The flag is carried all the way to the UI and the audit trail on purpose -
 * nothing in this system should ever be able to present a simulated submission as a real one.</p>
 *
 * @param submissionId     deterministic id derived from the case, package version and bundle hash,
 *                         so a retry produces the same id rather than a second submission
 * @param networkReference the reference the (simulated) network handed back
 * @param receiptObjectKey where the receipt JSON was written in {@code pdei-packages}
 */
public record SubmissionReceipt(
        String caseId,
        String submissionId,
        String networkReference,
        String submitterName,
        boolean simulated,
        boolean accepted,
        String statusDetail,
        int packageVersion,
        String bundleObjectKey,
        String bundleSha256,
        String receiptObjectKey,
        Instant submittedAt) {
}
