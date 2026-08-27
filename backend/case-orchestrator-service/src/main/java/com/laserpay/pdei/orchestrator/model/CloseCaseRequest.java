package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;

/**
 * Argument of activity 12, {@code closeCase}.
 *
 * @param targetDisputeStatus the dispute transition to attempt, or null to leave the dispute alone.
 *                            {@code DisputeService} validates the move against its transition table
 *                            and refuses illegal ones, so the workflow can ask without checking.
 * @param compensating        true when this call is the saga compensation for a failed run rather
 *                            than a normal closure; it forces {@link CaseStatus#FAILED} and counts
 *                            towards {@code pdei_workflow_failures_total}.
 */
public record CloseCaseRequest(
        CaseRef ref,
        CaseResolution resolution,
        CaseStatus targetCaseStatus,
        DisputeStatus targetDisputeStatus,
        String reason,
        String actor,
        boolean compensating,
        String idempotencyToken) {
}
