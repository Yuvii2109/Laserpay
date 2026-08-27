package com.laserpay.pdei.simulator.emit;

import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.persistence.entity.SimulationRunEntity;
import com.laserpay.pdei.persistence.repository.SimulationRunRepository;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persists run progress to {@code simulation_runs} and to Redis {@code pdei:sim:run:{runId}}.
 *
 * <p>Postgres is the durable record: a run that was interrupted by a restart is still visible,
 * with its seed and parameters, which is what makes "re-run that exact benchmark" possible.
 * Redis is the hot copy the simulation console polls every second - a UI refresh should not cost
 * a database round-trip, and losing the Redis key costs nothing because Postgres still has the
 * truth.
 *
 * <p>Writes here use {@code REQUIRES_NEW}. Progress is reported from a long-running emitter
 * thread that is deliberately <em>not</em> inside a transaction: holding one open for the
 * duration of a 200k-event run would pin a connection and bloat the transaction log for no
 * benefit.
 */
@Service
public class RunProgressStore {

    /** Platform contract 12: {@code pdei:sim:run:{runId}}. */
    public static final String REDIS_KEY_PREFIX = "pdei:sim:run:";

    private static final Logger log = LoggerFactory.getLogger(RunProgressStore.class);

    private final SimulationRunRepository runs;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final SimulatorProperties properties;
    private final Clocks clock;

    public RunProgressStore(SimulationRunRepository runs,
                            ObjectProvider<StringRedisTemplate> redisTemplates,
                            SimulatorProperties properties,
                            Clocks clock) {
        this.runs = runs;
        this.redisTemplates = redisTemplates;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates the {@code simulation_runs} row for a newly requested run. */
    @Transactional
    public SimulationRunEntity create(SimulationRunEntity run) {
        SimulationRunEntity saved = runs.save(run);
        writeRedis(toProgress(saved, 0L, 0L, 0L));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SimulationRunEntity> find(String runId) {
        return runs.findById(runId);
    }

    /**
     * Flushes a progress snapshot to both sinks.
     *
     * @param runId             the run
     * @param status            new status, or null to leave it unchanged
     * @param eventsEmitted     events published so far
     * @param eventsFailed      publish failures so far
     * @param artifactsUploaded synthetic artifacts written to MinIO
     * @param eventsPlanned     total events the generator produced
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunProgress update(String runId, String status, long eventsEmitted, long eventsFailed,
                              long artifactsUploaded, long eventsPlanned) {
        SimulationRunEntity run = runs.findById(runId).orElse(null);
        if (run == null) {
            log.warn("progress update for unknown run {}", runId);
            return null;
        }
        if (status != null) {
            run.setStatus(status);
        }
        run.setEventsEmitted(eventsEmitted);
        run.setProgressPercent(RunProgress.percent(eventsEmitted, eventsPlanned));
        if (run.getStartedAt() == null) {
            run.setStartedAt(clock.now());
        }
        if (isTerminal(run.getStatus()) && run.getFinishedAt() == null) {
            run.setFinishedAt(clock.now());
        }
        run.setStats(mergeStats(run.getStats(), eventsPlanned, eventsFailed, artifactsUploaded));

        SimulationRunEntity saved = runs.save(run);
        RunProgress progress = toProgress(saved, eventsFailed, artifactsUploaded, eventsPlanned);
        writeRedis(progress);
        return progress;
    }

    /** Records the counts the generator produced, before emission starts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGeneratedCounts(String runId, Map<String, Long> counts, long eventsPlanned) {
        runs.findById(runId).ifPresent(run -> {
            run.setTransactionsCreated(counts.getOrDefault("transactions", 0L));
            run.setEvidenceCreated(counts.getOrDefault("evidence", 0L));
            run.setDisputesCreated(counts.getOrDefault("disputes", 0L));
            Map<String, Object> stats = run.getStats() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(run.getStats());
            stats.put("eventsPlanned", eventsPlanned);
            counts.forEach(stats::put);
            run.setStats(stats);
            runs.save(run);
        });
    }

    /** Marks a run failed with a message; used from the runner's catch block. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String runId, String message) {
        runs.findById(runId).ifPresent(run -> {
            run.setStatus(SimulationRunEntity.STATUS_FAILED);
            run.setErrorMessage(abbreviate(message));
            run.setFinishedAt(clock.now());
            SimulationRunEntity saved = runs.save(run);
            writeRedis(toProgress(saved, 0L, 0L, saved.getEventsEmitted()));
        });
    }

    /** Reads the hot copy; falls back to Postgres when Redis is empty or unavailable. */
    public Optional<RunProgress> progress(String runId) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(REDIS_KEY_PREFIX + runId);
                if (json != null) {
                    return Optional.of(Json.read(json, RunProgress.class));
                }
            } catch (RuntimeException e) {
                log.debug("Redis progress read failed for {}: {}", runId, e.toString());
            }
        }
        return find(runId).map(run -> toProgress(run, 0L, 0L, plannedOf(run)));
    }

    /** Projects an entity into the wire shape. */
    public RunProgress toProgress(SimulationRunEntity run, long eventsFailed,
                                  long artifactsUploaded, long eventsPlanned) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (run.getStats() != null) {
            run.getStats().forEach((key, value) -> {
                if (value instanceof Number number) {
                    counts.put(key, number.longValue());
                }
            });
        }
        long planned = eventsPlanned > 0 ? eventsPlanned : plannedOf(run);
        return new RunProgress(
                run.getId(),
                run.getSeed(),
                run.getScenarioKey(),
                run.getStatus(),
                run.getProgressPercent(),
                planned,
                run.getEventsEmitted(),
                eventsFailed,
                artifactsUploaded,
                run.getTransactionsCreated(),
                run.getEvidenceCreated(),
                run.getDisputesCreated(),
                properties.getEmit().getEventsPerSecond(),
                run.getStartedAt(),
                clock.now(),
                run.getFinishedAt(),
                run.getErrorMessage(),
                counts);
    }

    private void writeRedis(RunProgress progress) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null || progress == null) {
            return;
        }
        try {
            redis.opsForValue().set(REDIS_KEY_PREFIX + progress.runId(), Json.write(progress),
                    properties.getRuns().getRedisTtl());
        } catch (RuntimeException e) {
            // Redis is the convenience copy; Postgres already has the durable record.
            log.debug("Redis progress write failed for {}: {}", progress.runId(), e.toString());
        }
    }

    private static Map<String, Object> mergeStats(Map<String, Object> existing, long eventsPlanned,
                                                  long eventsFailed, long artifactsUploaded) {
        Map<String, Object> stats = existing == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existing);
        stats.put("eventsPlanned", eventsPlanned);
        stats.put("eventsFailed", eventsFailed);
        stats.put("artifactsUploaded", artifactsUploaded);
        return stats;
    }

    private static long plannedOf(SimulationRunEntity run) {
        Object planned = run.getStats() == null ? null : run.getStats().get("eventsPlanned");
        return planned instanceof Number number ? number.longValue() : run.getEventsEmitted();
    }

    private static boolean isTerminal(String status) {
        return SimulationRunEntity.STATUS_COMPLETED.equals(status)
                || SimulationRunEntity.STATUS_FAILED.equals(status)
                || SimulationRunEntity.STATUS_STOPPED.equals(status);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    /** Instant supplied by the injected clock, exposed for the runner's own timestamps. */
    public Instant now() {
        return clock.now();
    }
}
