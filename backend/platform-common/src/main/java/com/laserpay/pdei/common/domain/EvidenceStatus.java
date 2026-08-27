package com.laserpay.pdei.common.domain;

/**
 * Lifecycle of an evidence item (PLATFORM-CONTRACT section 6).
 *
 * <p>Only {@link #ACTIVE} and {@link #EXPIRING} evidence can satisfy a requirement; the readiness
 * engine treats the rest as absent and raises a gap. History is never deleted - superseded and
 * invalidated items remain queryable for provenance (reference section 12).
 */
public enum EvidenceStatus {
    /** Registered but not yet verified (hash pending, document not yet processed). */
    PENDING,
    /** Verified and usable. */
    ACTIVE,
    /** Still usable but inside the expiry warning window (7 days by default). */
    EXPIRING,
    /** Past its validity window; no longer satisfies a requirement. */
    EXPIRED,
    /** Proven wrong, tampered with, or contradicted; excluded from every package. */
    INVALIDATED,
    /** Replaced by a newer version of the same logical evidence. */
    SUPERSEDED;

    /** Whether evidence in this status can satisfy a requirement. */
    public boolean isUsable() {
        return this == ACTIVE || this == EXPIRING;
    }

    /** Terminal statuses never transition again. */
    public boolean isTerminal() {
        return this == INVALIDATED || this == SUPERSEDED;
    }
}
