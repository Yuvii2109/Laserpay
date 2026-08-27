package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;

import java.time.Instant;

/**
 * Result of activity 12, {@code closeCase}.
 *
 * @param disputeTransitioned false when {@code DisputeService} refused the requested transition
 *                            (for example the dispute was already terminal). That is information,
 *                            not a failure: the case still closes.
 */
public record CloseCaseResult(
        String caseId,
        CaseStatus caseStatus,
        DisputeStatus disputeStatus,
        boolean disputeTransitioned,
        CaseResolution resolution,
        Instant closedAt) {
}
