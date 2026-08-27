package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.CaseStatus;
import java.time.Instant;

/**
 * Result of a human decision on a case.
 *
 * <p>{@code deliveredTo} is the honest record of how the decision actually reached the workflow:
 * {@code TEMPORAL_SIGNAL} when case-orchestrator-service accepted it, or {@code LOCAL_TRANSITION}
 * when the orchestrator was unreachable and the gateway applied the deterministic fallback (status
 * change plus CASE event plus audit entry) itself. The frontend shows the difference, because a case
 * advanced locally still has a workflow that has not heard about it.</p>
 *
 * @param signal the Temporal signal name attempted (contract section 10: {@code humanDecision})
 */
public record CaseDecisionResponse(
        String caseId,
        String decision,
        String signal,
        CaseStatus previousStatus,
        CaseStatus status,
        String deliveredTo,
        String actor,
        String note,
        Instant decidedAt) {

    public static final String TEMPORAL_SIGNAL = "TEMPORAL_SIGNAL";
    public static final String LOCAL_TRANSITION = "LOCAL_TRANSITION";
}
