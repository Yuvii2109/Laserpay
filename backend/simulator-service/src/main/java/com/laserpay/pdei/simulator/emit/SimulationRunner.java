package com.laserpay.pdei.simulator.emit;

import com.laserpay.pdei.common.error.ConflictException;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.persistence.entity.SimulationRunEntity;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import com.laserpay.pdei.simulator.world.FailureMix;
import com.laserpay.pdei.simulator.world.FailureProfile;
import com.laserpay.pdei.simulator.world.GeneratedWorld;
import com.laserpay.pdei.simulator.world.SimEvent;
import com.laserpay.pdei.simulator.world.WorldGenerator;
import com.laserpay.pdei.simulator.world.WorldSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the lifecycle of a simulation run: generate, record, upload, emit, finish.
 *
 * <p>Runs are asynchronous by necessity - emitting a hundred thousand events at 200/second takes
 * eight minutes, and an HTTP request cannot hold that. {@code POST /sim/v1/runs} therefore
 * returns a {@code SIM-} id immediately and the caller watches
 * {@code GET /sim/v1/runs/{runId}}, which reads the progress model this class maintains.
 *
 * <p>Concurrency is capped ({@code pdei.simulator.runs.max-concurrent}) because two runs sharing
 * one broker and one database do not go twice as fast; they interleave and make each other's
 * benchmark numbers meaningless.
 *
 * <p>The live {@link EmissionControl} of every active run is kept in {@link #controls} so the
 * chaos engine and the stop endpoint can reach a run that is already in flight.
 */
@Service
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);

    private final WorldGenerator generator;
    private final EventEmitter emitter;
    private final ArtifactUploader uploader;
    private final RunProgressStore progressStore;
    private final SimulatorProperties properties;
    private final ExecutorService runExecutor;
    private final Clocks clock;

    /** Live control surfaces, keyed by run id. Entries are removed when a run finishes. */
    private final Map<String, EmissionControl> controls = new ConcurrentHashMap<>();

    /** Retained generated streams, so chaos can target "an event from this run". */
    private final Map<String, List<SimEvent>> retainedStreams = new ConcurrentHashMap<>();

    private final AtomicInteger active = new AtomicInteger();

    public SimulationRunner(WorldGenerator generator,
                            EventEmitter emitter,
                            ArtifactUploader uploader,
                            RunProgressStore progressStore,
                            SimulatorProperties properties,
                            ExecutorService runExecutor,
                            Clocks clock) {
        this.generator = generator;
        this.emitter = emitter;
        this.uploader = uploader;
        this.progressStore = progressStore;
        this.properties = properties;
        this.runExecutor = runExecutor;
        this.clock = clock;
    }

    /**
     * Starts a run.
     *
     * @param spec        the world to generate
     * @param requestedBy actor for the audit trail
     * @return the persisted {@code simulation_runs} row, already in PENDING
     * @throws ConflictException when {@code max-concurrent} runs are already going
     */
    public SimulationRunEntity start(WorldSpec spec, String requestedBy) {
        int inFlight = active.get();
        if (inFlight >= properties.getRuns().getMaxConcurrent()) {
            throw new ConflictException("simulator already has " + inFlight
                    + " runs in flight (max " + properties.getRuns().getMaxConcurrent() + ")");
        }

        SimulationRunEntity run = new SimulationRunEntity();
        run.setId(Ids.simulation());
        run.setSeed(spec.seed());
        run.setMerchantCount(spec.merchants());
        run.setTransactionCount(spec.transactions());
        run.setDays(spec.days());
        run.setDisputeRateBps(spec.disputeRateBps());
        run.setScenarioKey(spec.scenarioKey());
        run.setFailureProfile(describeMix(spec));
        run.setStatus(SimulationRunEntity.STATUS_PENDING);
        run.setRequestedBy(requestedBy == null ? "api" : requestedBy);
        run.setParams(paramsOf(spec));

        SimulationRunEntity saved = progressStore.create(run);
        active.incrementAndGet();
        runExecutor.submit(() -> execute(saved.getId(), spec));
        log.info("simulation run {} accepted: seed={} merchants={} transactions={} days={} scenario={}",
                saved.getId(), spec.seed(), spec.merchants(), spec.transactions(), spec.days(),
                spec.scenarioKey());
        return saved;
    }

    /**
     * Requests a cooperative stop. The emitter checks between events, so a stop takes effect
     * within one event rather than immediately - which is the honest behaviour: an event already
     * handed to the producer cannot be recalled.
     *
     * @throws NotFoundException when the run does not exist
     */
    public RunProgress stop(String runId) {
        EmissionControl control = controls.get(runId);
        if (control != null) {
            control.stop();
            progressStore.update(runId, SimulationRunEntity.STATUS_STOPPING, currentEmitted(runId),
                    0L, 0L, plannedOf(runId));
        } else if (progressStore.find(runId).isEmpty()) {
            throw new NotFoundException("simulation run", runId);
        }
        return progressStore.progress(runId).orElse(null);
    }

    /** The live control surface of a running simulation, for the chaos engine. */
    public Optional<EmissionControl> control(String runId) {
        return Optional.ofNullable(controls.get(runId));
    }

    /** Any running simulation, for chaos requests that do not name one. */
    public Optional<EmissionControl> anyActiveControl() {
        return controls.values().stream().findFirst();
    }

    /** The retained event stream of a run, for chaos types that re-publish real traffic. */
    public List<SimEvent> retainedStream(String runId) {
        return retainedStreams.getOrDefault(runId, List.of());
    }

    /** Any retained stream, newest run first is not guaranteed - callers just need some traffic. */
    public Optional<List<SimEvent>> anyRetainedStream() {
        return retainedStreams.values().stream().filter(list -> !list.isEmpty()).findFirst();
    }

    public int activeRuns() {
        return active.get();
    }

    // -------------------------------------------------------------------------------------
    // The run itself
    // -------------------------------------------------------------------------------------

    private void execute(String runId, WorldSpec spec) {
        EmissionControl control = new EmissionControl(runId,
                properties.getChaos().getRecentEventBuffer());
        controls.put(runId, control);
        try {
            GeneratedWorld world = generator.generate(spec);
            progressStore.recordGeneratedCounts(runId, world.counts(), world.eventCount());
            retainStream(runId, world);

            long uploaded = uploader.upload(world.artifacts());
            progressStore.update(runId, SimulationRunEntity.STATUS_RUNNING, 0L, 0L, uploaded,
                    world.eventCount());

            EventEmitter.EmissionResult result = emitter.emit(world, control,
                    (emitted, failed) -> progressStore.update(runId, SimulationRunEntity.STATUS_RUNNING,
                            emitted, failed, uploaded, world.eventCount()));

            String finalStatus = result.stopped()
                    ? SimulationRunEntity.STATUS_STOPPED
                    : SimulationRunEntity.STATUS_COMPLETED;
            progressStore.update(runId, finalStatus, result.emitted(), result.failed(), uploaded,
                    world.eventCount());
            log.info("simulation run {} {} at {}", runId, finalStatus, clock.now());

        } catch (RuntimeException e) {
            log.error("simulation run {} failed", runId, e);
            progressStore.fail(runId, e.toString());
        } finally {
            controls.remove(runId);
            active.decrementAndGet();
        }
    }

    private void retainStream(String runId, GeneratedWorld world) {
        if (!properties.getRuns().isRetainStream()) {
            return;
        }
        int limit = Math.max(0, properties.getRuns().getRetainedStreamLimit());
        List<SimEvent> events = world.events();
        retainedStreams.put(runId, events.size() > limit
                ? List.copyOf(events.subList(0, limit))
                : events);
    }

    private long currentEmitted(String runId) {
        return progressStore.find(runId).map(SimulationRunEntity::getEventsEmitted).orElse(0L);
    }

    private long plannedOf(String runId) {
        return progressStore.progress(runId).map(RunProgress::eventsPlanned).orElse(0L);
    }

    /**
     * Serialises the spec into {@code simulation_runs.params}. This is the reproducibility
     * record: seed plus these parameters is everything needed to regenerate the identical world,
     * which is exactly what "reproducible workloads via deterministic seeds" requires.
     */
    private static Map<String, Object> paramsOf(WorldSpec spec) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("seed", spec.seed());
        params.put("merchants", spec.merchants());
        params.put("transactions", spec.transactions());
        params.put("days", spec.days());
        params.put("disputeRateBps", spec.disputeRateBps());
        params.put("currency", spec.currency());
        params.put("startAt", spec.startAt().toString());
        params.put("scenarioKey", spec.scenarioKey());
        params.put("forcedReasonCode",
                spec.forcedReasonCode() == null ? null : spec.forcedReasonCode().name());
        params.put("minAmountMinor", spec.minAmountMinor());
        params.put("disputeDeadlineDays", spec.disputeDeadlineDays());
        params.put("failureMix", Json.tree(spec.failureMix()));
        return params;
    }

    /** Short label for {@code simulation_runs.failure_profile} (VARCHAR(64)). */
    private static String describeMix(WorldSpec spec) {
        if (spec.scenarioKey() != null) {
            String label = "SCENARIO:" + spec.scenarioKey();
            return label.length() > 64 ? label.substring(0, 64) : label;
        }
        FailureMix mix = spec.failureMix();
        if (mix.equals(FailureMix.clean())) {
            return FailureProfile.CLEAN.name();
        }
        if (mix.equals(FailureMix.hostile())) {
            return FailureProfile.HOSTILE.name();
        }
        if (mix.equals(FailureMix.realistic())) {
            return FailureProfile.REALISTIC.name();
        }
        return "CUSTOM";
    }
}
