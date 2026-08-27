package com.laserpay.pdei.orchestrator.model;

/**
 * Argument of activity 10, {@code submitRepresentment}.
 *
 * <p>The package coordinates are passed rather than re-derived so the activity submits exactly the
 * bundle step 9 produced, even if another package version were assembled in between.</p>
 */
public record SubmitRequest(
        CaseRef ref,
        PackageResult packageResult,
        String submittedBy,
        String idempotencyToken) {
}
