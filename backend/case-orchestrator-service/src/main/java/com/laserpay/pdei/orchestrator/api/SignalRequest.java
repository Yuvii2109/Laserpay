package com.laserpay.pdei.orchestrator.api;

import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.orchestrator.model.HumanDecisionType;

/**
 * Body of {@code POST /orchestrator/v1/cases/{caseId}/signal} - the generic dispatcher.
 *
 * <p>Only the fields relevant to {@code signal} are read; the rest are ignored. The dedicated
 * routes ({@code /approve}, {@code /reject}, {@code /submit}, {@code /evidence-arrived},
 * {@code /cancel}) exist because they are what the api-gateway actually calls, and they are easier
 * to read in an access log than a generic body.</p>
 *
 * @param signal one of {@code evidenceArrived}, {@code humanDecision}, {@code disputeUpdated},
 *               {@code cancelCase} - the four signal names of PLATFORM-CONTRACT section 10
 */
public record SignalRequest(
        String signal,
        HumanDecisionType decision,
        String actor,
        String notes,
        String evidenceId,
        EvidenceType evidenceType,
        DisputeStatus disputeStatus,
        String eventId,
        String reason) {
}
