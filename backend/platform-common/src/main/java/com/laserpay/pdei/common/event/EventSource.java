package com.laserpay.pdei.common.event;

/**
 * Origin system of a canonical event (PLATFORM-CONTRACT section 3).
 *
 * <p>Provenance is a first-class field: an evidence item derived from a {@code SIMULATOR} event is
 * never presented as if it came from a {@code PSP_ADAPTER}, and the readiness engine can penalise
 * unverifiable provenance.
 *
 * <p>Distinct from {@code common.domain.EvidenceSource}, which classifies where an evidence
 * artifact came from (and includes {@code DOCUMENT_UPLOAD} / {@code INTERNAL_DERIVED}).
 */
public enum EventSource {
    PSP_ADAPTER,
    ORDER_SYSTEM,
    LOGISTICS,
    CRM,
    SIMULATOR,
    INTERNAL,
    MERCHANT_PORTAL
}
