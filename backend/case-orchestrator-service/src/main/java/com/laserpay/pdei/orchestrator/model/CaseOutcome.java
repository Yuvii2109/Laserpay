package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;

import java.util.List;

/**
 * Return value of {@code DisputeCaseWorkflow.run}. It is stored in the Temporal event history, so
 * it is the durable record of what the workflow concluded, independent of the read model.
 */
public record CaseOutcome(
        String caseId,
        String disputeId,
        CaseResolution resolution,
        CaseStatus caseStatus,
        DisputeStatus disputeStatus,
        int packageVersion,
        String bundleObjectKey,
        String networkReference,
        String reason,
        List<String> completedSteps,
        int assessmentRounds,
        int continuationCount) {

    public CaseOutcome {
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
    }
}
