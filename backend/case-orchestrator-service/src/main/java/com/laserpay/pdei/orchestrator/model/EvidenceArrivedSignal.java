package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.EvidenceType;

import java.time.Instant;

/**
 * Payload of the {@code evidenceArrived} signal.
 *
 * <p>Sent by the api-gateway (merchant portal upload) or by any component that observed an
 * {@code EvidenceAdded} event for a transaction that has an open case. The workflow does not trust
 * the payload as evidence of anything: it only uses it as a wake-up, then re-runs
 * {@code gatherEvidence} and {@code detectGaps} against Postgres.</p>
 *
 * <p>Duplicate deliveries are harmless. A repeated {@code evidenceId} is deduplicated by the
 * workflow, and a wake-up with nothing new behind it simply re-evaluates the gap report.</p>
 */
public record EvidenceArrivedSignal(
        String evidenceId,
        EvidenceType evidenceType,
        String sourceEventId,
        Instant arrivedAt) {

    public static EvidenceArrivedSignal of(String evidenceId, EvidenceType evidenceType) {
        return new EvidenceArrivedSignal(evidenceId, evidenceType, null, null);
    }
}
