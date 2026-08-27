package com.laserpay.pdei.orchestrator.model;

import java.time.Instant;
import java.util.List;

/**
 * Answer of the {@code getProgress} query, shaped for the SSE stream
 * {@code GET /api/v1/stream/cases/{caseId}} and for the case queue swimlanes.
 *
 * @param waitingFor human-readable description of the timer or signal the case is parked on, or
 *                   {@code null} when the workflow is actively executing an activity
 */
public record CaseProgress(
        String caseId,
        CasePhase phase,
        int step,
        int totalSteps,
        int percent,
        String description,
        List<String> completedSteps,
        boolean waiting,
        String waitingFor,
        Instant deadlineAt,
        boolean terminal) {

    public CaseProgress {
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
    }
}
