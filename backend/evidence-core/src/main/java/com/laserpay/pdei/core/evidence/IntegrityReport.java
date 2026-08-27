package com.laserpay.pdei.core.evidence;

import java.time.Instant;

/**
 * Outcome of re-hashing a stored object and comparing it to the sha256 recorded at capture time.
 *
 * @param intact     true when the stored bytes still hash to the recorded value
 * @param expectedSha256 the hash recorded in Postgres when the artifact was captured
 * @param actualSha256   the hash of the bytes currently in MinIO, or null when unreadable
 * @param objectMissing  true when the object could not be read at all
 */
public record IntegrityReport(
        String evidenceId,
        String objectKey,
        boolean intact,
        boolean objectMissing,
        String expectedSha256,
        String actualSha256,
        String detail,
        Instant verifiedAt) {

    public static IntegrityReport ok(String evidenceId, String objectKey, String sha256, Instant at) {
        return new IntegrityReport(evidenceId, objectKey, true, false, sha256, sha256, null, at);
    }

    public static IntegrityReport mismatch(String evidenceId, String objectKey, String expected,
                                           String actual, Instant at) {
        return new IntegrityReport(evidenceId, objectKey, false, false, expected, actual,
                "stored object hash does not match the recorded sha256", at);
    }

    public static IntegrityReport missing(String evidenceId, String objectKey, String expected,
                                          String detail, Instant at) {
        return new IntegrityReport(evidenceId, objectKey, false, true, expected, null, detail, at);
    }
}
