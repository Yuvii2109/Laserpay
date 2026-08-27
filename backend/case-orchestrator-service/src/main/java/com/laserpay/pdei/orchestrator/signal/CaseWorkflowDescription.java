package com.laserpay.pdei.orchestrator.signal;

import java.time.Instant;

/**
 * What Temporal knows about a case's execution, flattened for
 * {@code GET /orchestrator/v1/cases/{caseId}/describe}.
 *
 * <p>This is the operator's view: is the workflow running, which run is current, how large has its
 * history grown, when did it start. It complements {@code getCaseState}, which is the case's own
 * view of itself.</p>
 */
public record CaseWorkflowDescription(
        String caseId,
        String workflowId,
        String runId,
        String workflowType,
        String taskQueue,
        String status,
        long historyLength,
        Instant startTime,
        Instant executionTime,
        Instant closeTime,
        boolean running) {

    /** Returned when Temporal has no execution with this workflow id. */
    public static CaseWorkflowDescription notFound(String caseId, String workflowId) {
        return new CaseWorkflowDescription(caseId, workflowId, null, null, null, "NOT_FOUND", 0L,
                null, null, null, false);
    }
}
