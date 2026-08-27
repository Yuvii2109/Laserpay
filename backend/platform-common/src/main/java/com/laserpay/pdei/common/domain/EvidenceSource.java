package com.laserpay.pdei.common.domain;

/**
 * Where an evidence artifact came from (PLATFORM-CONTRACT section 6).
 *
 * <p>Provenance drives trust: {@link #INTERNAL_DERIVED} evidence (produced by PDEI itself, e.g. a
 * timeline reconstruction) is weaker than a {@link #PSP_ADAPTER} record, and unverifiable
 * provenance on mandatory evidence costs 20 readiness points (PLATFORM-CONTRACT section 7).
 *
 * <p>Overlaps by name with {@code common.event.EventSource} but is a different vocabulary: this one
 * classifies artifacts, that one classifies events.
 */
public enum EvidenceSource {
    PSP_ADAPTER,
    ORDER_SYSTEM,
    LOGISTICS,
    CRM,
    DOCUMENT_UPLOAD,
    MERCHANT_PORTAL,
    SIMULATOR,
    INTERNAL_DERIVED
}
