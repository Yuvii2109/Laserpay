package com.laserpay.pdei.simulator.emit;

import com.laserpay.pdei.persistence.entity.SimulationRunEntity;

import java.time.Instant;
import java.util.Map;

/**
 * Live progress of one simulation run.
 *
 * <p>This is the shape written to Redis under {@code pdei:sim:run:{runId}} (platform contract 12)
 * and returned by {@code GET /sim/v1/runs/{runId}}. Two sinks, one shape: Postgres
 * ({@code simulation_runs}) is the durable record that survives a restart, Redis is the hot copy
 * the console polls without touching the database. Neither is derived from the other, so a Redis
 * flush loses nothing.
 *
 * @param runId              {@code SIM-} id
 * @param seed               the reproducibility seed
 * @param scenarioKey        curated scenario, null for an ad-hoc run
 * @param status             one of the {@code SimulationRunEntity.STATUS_*} values
 * @param progressPercent    0-100
 * @param eventsPlanned      events the generator produced
 * @param eventsEmitted      events published so far
 * @param eventsFailed       events whose publish failed
 * @param artifactsUploaded  synthetic evidence objects written to MinIO
 * @param transactionsCreated transactions in the generated world
 * @param evidenceCreated    evidence artifacts in the generated world
 * @param disputesCreated    disputes in the generated world
 * @param eventsPerSecond    configured emission rate
 * @param startedAt          when emission began
 * @param updatedAt          when this snapshot was taken
 * @param finishedAt         when the run ended, null while running
 * @param errorMessage       failure detail, null unless the run failed
 * @param counts             the generator's own headline counts
 */
public record RunProgress(String runId,
                          long seed,
                          String scenarioKey,
                          String status,
                          int progressPercent,
                          long eventsPlanned,
                          long eventsEmitted,
                          long eventsFailed,
                          long artifactsUploaded,
                          long transactionsCreated,
                          long evidenceCreated,
                          long disputesCreated,
                          int eventsPerSecond,
                          Instant startedAt,
                          Instant updatedAt,
                          Instant finishedAt,
                          String errorMessage,
                          Map<String, Long> counts) {

    public RunProgress {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    public boolean isTerminal() {
        return SimulationRunEntity.STATUS_COMPLETED.equals(status)
                || SimulationRunEntity.STATUS_FAILED.equals(status)
                || SimulationRunEntity.STATUS_STOPPED.equals(status);
    }

    /** Progress as a whole percent, clamped, with the divide-by-zero case handled. */
    public static int percent(long emitted, long planned) {
        if (planned <= 0) {
            return 0;
        }
        long pct = emitted * 100L / planned;
        return (int) Math.max(0L, Math.min(100L, pct));
    }
}
