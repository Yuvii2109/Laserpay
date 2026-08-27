package com.laserpay.pdei.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stored evidence failed its integrity check: the recomputed SHA-256 does not match the recorded
 * hash, the object is missing from MinIO, or a version chain is broken
 * (reference section 12, PLATFORM-CONTRACT section 11).
 *
 * <p>This is a hard financial-evidence failure: the evidence must be treated as untrustworthy and
 * the incident audited.
 */
public final class EvidenceIntegrityException extends PdeiException {

    public static final String CODE = "EVIDENCE_INTEGRITY";

    public EvidenceIntegrityException(String message) {
        super(CODE, 409, message, null, null);
    }

    public EvidenceIntegrityException(String message, Map<String, Object> details) {
        super(CODE, 409, message, details, null);
    }

    /** Hash-mismatch factory carrying both hashes for the audit record. */
    public static EvidenceIntegrityException hashMismatch(String evidenceId,
                                                          String expectedSha256,
                                                          String actualSha256) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("evidenceId", evidenceId);
        details.put("expectedSha256", expectedSha256);
        details.put("actualSha256", actualSha256);
        return new EvidenceIntegrityException(
                "SHA-256 mismatch for evidence " + evidenceId, details);
    }
}
