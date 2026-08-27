package com.laserpay.pdei.common.domain;

/**
 * Why a transaction is not evidence-ready (PLATFORM-CONTRACT section 6).
 *
 * <p>Gaps are the product: detecting them <em>before</em> a dispute exists is the entire point of
 * the platform (reference section 4.2). Several types carry a direct readiness penalty
 * (PLATFORM-CONTRACT section 7): CONTRADICTORY -15, EXPIRED -10, EXPIRING_SOON -5, and
 * UNVERIFIABLE_PROVENANCE -20 when it touches mandatory evidence.
 */
public enum GapType {
    /** A required evidence type is not present at all. */
    MISSING,
    /** Present but past its validity window. */
    EXPIRED,
    /** Present and valid, but expiring inside the warning window. */
    EXPIRING_SOON,
    /** Two evidence items disagree on a material field (e.g. delivery date). */
    CONTRADICTORY,
    /** Present, but its origin cannot be verified. */
    UNVERIFIABLE_PROVENANCE,
    /** Present but unusable: unreadable scan, empty document, failed extraction. */
    LOW_QUALITY,
    /** Multiple versions disagree and no version is authoritative. */
    VERSION_CONFLICT
}
