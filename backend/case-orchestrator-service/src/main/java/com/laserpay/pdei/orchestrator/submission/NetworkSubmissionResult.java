package com.laserpay.pdei.orchestrator.submission;

import java.time.Instant;

/**
 * What the network (or the simulator standing in for it) said about a submission.
 *
 * @param simulated true when no real network was contacted. This flag is propagated all the way to
 *                  {@code SubmissionReceipt}, the {@code CaseSubmitted} event, the case row and the
 *                  UI: a simulated submission must never be able to look like a real one.
 */
public record NetworkSubmissionResult(
        String submissionId,
        String networkReference,
        boolean accepted,
        String statusDetail,
        String submitterName,
        boolean simulated,
        Instant submittedAt) {
}
