package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.persistence.entity.SimulationRunEntity;
import com.laserpay.pdei.simulator.emit.RunProgress;

import java.time.Instant;
import java.util.Map;

/**
 * A simulation run as the API renders it: {@code POST /sim/v1/runs},
 * {@code GET /sim/v1/runs} and {@code GET /sim/v1/runs/{runId}}.
 *
 * <p>{@code seed} and {@code params} are the reproducibility record - together they are
 * everything needed to regenerate this exact world, which is what makes a benchmark repeatable
 * rather than anecdotal.
 */
public record RunViewDto(String runId,
                         long seed,
                         String scenarioKey,
                         String status,
                         int progressPercent,
                         int merchants,
                         int transactions,
                         int days,
                         int disputeRateBps,
                         String failureProfile,
                         long eventsPlanned,
                         long eventsEmitted,
                         long transactionsCreated,
                         long evidenceCreated,
                         long disputesCreated,
                         Instant startedAt,
                         Instant finishedAt,
                         Instant createdAt,
                         String requestedBy,
                         String errorMessage,
                         Map<String, Object> params) {

    public static RunViewDto from(SimulationRunEntity run, long eventsPlanned) {
        return new RunViewDto(
                run.getId(),
                run.getSeed(),
                run.getScenarioKey(),
                run.getStatus(),
                run.getProgressPercent(),
                run.getMerchantCount(),
                run.getTransactionCount(),
                run.getDays(),
                run.getDisputeRateBps(),
                run.getFailureProfile(),
                eventsPlanned,
                run.getEventsEmitted(),
                run.getTransactionsCreated(),
                run.getEvidenceCreated(),
                run.getDisputesCreated(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getCreatedAt(),
                run.getRequestedBy(),
                run.getErrorMessage(),
                run.getParams());
    }

    /** The detail view pairs the durable row with the live progress snapshot. */
    public record RunDetailDto(RunViewDto run, RunProgress progress) {
    }
}
