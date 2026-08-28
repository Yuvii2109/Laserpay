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

    /**
     * The same view, with a live progress snapshot laid over the durable row's counters.
     *
     * <p>{@code GET /runs/{runId}} used to return {@code {"run": …, "progress": …}} while
     * {@code GET /runs} returned bare run objects. Contract §8.5 promises "progress + stats" and
     * says nothing about an envelope, so both callers - {@code scripts/seed-demo.sh} and the
     * frontend's {@code api.get<SimulationRun>} - read {@code status} from the top level and got
     * nothing. Every seeded run therefore printed {@code events=?} and ended with "did not report
     * completion within 300s" after finishing in fifteen seconds.
     *
     * <p>Overlaying rather than wrapping keeps what the envelope was actually for: the durable row
     * is written periodically, so mid-run its counters lag the emitter. Where a live snapshot
     * exists it wins; where it does not, the row stands on its own.
     */
    public RunViewDto withLiveProgress(RunProgress progress) {
        if (progress == null) {
            return this;
        }
        return new RunViewDto(runId, seed, scenarioKey,
                progress.status() != null ? progress.status() : status,
                progress.progressPercent(),
                merchants, transactions, days, disputeRateBps, failureProfile,
                progress.eventsPlanned(),
                progress.eventsEmitted(),
                progress.transactionsCreated(),
                progress.evidenceCreated(),
                progress.disputesCreated(),
                progress.startedAt() != null ? progress.startedAt() : startedAt,
                progress.finishedAt() != null ? progress.finishedAt() : finishedAt,
                createdAt, requestedBy,
                progress.errorMessage() != null ? progress.errorMessage() : errorMessage,
                params);
    }
}
