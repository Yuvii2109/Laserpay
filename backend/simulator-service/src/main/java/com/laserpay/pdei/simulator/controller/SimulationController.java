package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.persistence.entity.ChaosInjectionEntity;
import com.laserpay.pdei.persistence.entity.SimulationRunEntity;
import com.laserpay.pdei.persistence.repository.ChaosInjectionRepository;
import com.laserpay.pdei.persistence.repository.SimulationRunRepository;
import com.laserpay.pdei.simulator.chaos.ChaosEngine;
import com.laserpay.pdei.simulator.chaos.ChaosResult;
import com.laserpay.pdei.simulator.emit.RunProgress;
import com.laserpay.pdei.simulator.emit.RunProgressStore;
import com.laserpay.pdei.simulator.emit.SimulationRunner;
import com.laserpay.pdei.simulator.replay.ReplayResult;
import com.laserpay.pdei.simulator.replay.ReplayService;
import com.laserpay.pdei.simulator.world.Scenario;
import com.laserpay.pdei.simulator.world.ScenarioLibrary;
import com.laserpay.pdei.simulator.world.WorldSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST surface of simulator-service, platform contract section 8.5.
 *
 * <pre>
 * POST /sim/v1/runs                   {seed, merchants, transactions, days, disputeRate, failureProfile} -&gt; runId
 * GET  /sim/v1/runs                   list
 * GET  /sim/v1/runs/{runId}           progress + stats
 * POST /sim/v1/runs/{runId}/stop
 * POST /sim/v1/chaos                  {type, target, delayMs?, count?} -&gt; injectionId
 * GET  /sim/v1/chaos                  injection history
 * POST /sim/v1/replay                 {topic, fromOffset|fromTimestamp, merchantId?}
 * GET  /sim/v1/scenarios              curated demo scenarios
 * POST /sim/v1/scenarios/{key}/run
 * </pre>
 *
 * <p>Runs are asynchronous: {@code POST /runs} returns {@code 202 Accepted} with the {@code SIM-}
 * id and the caller polls {@code GET /runs/{runId}}. Chaos and replay are synchronous, because
 * both are bounded and because the operator who pressed the button wants to know immediately
 * whether the injection landed.
 */
@RestController
@RequestMapping("/sim/v1")
@Validated
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);
    private static final int MAX_PAGE_SIZE = 200;

    private final SimulationRunner runner;
    private final RunProgressStore progressStore;
    private final SimulationRunRepository runs;
    private final ChaosEngine chaosEngine;
    private final ChaosInjectionRepository injections;
    private final ReplayService replayService;

    public SimulationController(SimulationRunner runner,
                                RunProgressStore progressStore,
                                SimulationRunRepository runs,
                                ChaosEngine chaosEngine,
                                ChaosInjectionRepository injections,
                                ReplayService replayService) {
        this.runner = runner;
        this.progressStore = progressStore;
        this.runs = runs;
        this.chaosEngine = chaosEngine;
        this.injections = injections;
        this.replayService = replayService;
    }

    // -------------------------------------------------------------------------------------
    // Runs
    // -------------------------------------------------------------------------------------

    /**
     * Starts a run.
     *
     * @return {@code 202 Accepted} with the run row; generation and emission continue in the
     *     background
     */
    @PostMapping("/runs")
    public ResponseEntity<RunViewDto> createRun(@Valid @RequestBody CreateRunRequestDto request) {
        WorldSpec spec = request.toSpec();
        SimulationRunEntity run = runner.start(spec, request.actor());
        log.info("run {} created from API: seed={} transactions={}", run.getId(), spec.seed(),
                spec.transactions());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RunViewDto.from(run, 0L));
    }

    /** Most recent runs first. */
    @GetMapping("/runs")
    public List<RunViewDto> listRuns(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        Page<SimulationRunEntity> found = runs.findAllByOrderByCreatedAtDesc(
                PageRequest.of(Math.max(0, page), clampSize(size)));
        return found.getContent().stream().map(run -> RunViewDto.from(run, plannedOf(run))).toList();
    }

    /** The durable row plus the live progress snapshot. */
    @GetMapping("/runs/{runId}")
    public RunViewDto.RunDetailDto getRun(@PathVariable @NotBlank @Size(max = 64) String runId) {
        SimulationRunEntity run = runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("simulation run", runId));
        RunProgress progress = progressStore.progress(runId).orElse(null);
        long planned = progress != null ? progress.eventsPlanned() : plannedOf(run);
        return new RunViewDto.RunDetailDto(RunViewDto.from(run, planned), progress);
    }

    /**
     * Requests a cooperative stop.
     *
     * <p>Cooperative, not immediate: the emitter checks between events, and an event already
     * handed to the producer cannot be recalled. Pretending otherwise would be a lie the progress
     * numbers would immediately expose.
     */
    @PostMapping("/runs/{runId}/stop")
    public RunProgress stopRun(@PathVariable @NotBlank @Size(max = 64) String runId) {
        return runner.stop(runId);
    }

    // -------------------------------------------------------------------------------------
    // Chaos
    // -------------------------------------------------------------------------------------

    /** Injects one failure and returns what happened, including the {@code injectionId}. */
    @PostMapping("/chaos")
    public ChaosResult injectChaos(@RequestBody ChaosRequestDto request) {
        return chaosEngine.inject(request.toRequest());
    }

    /** Injection history, most recent first; optionally narrowed to one run. */
    @GetMapping("/chaos")
    public List<ChaosViewDto> chaosHistory(@RequestParam(required = false) String runId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        if (runId != null && !runId.isBlank()) {
            return injections.findByRunIdOrderByInjectedAtDesc(runId).stream()
                    .map(ChaosViewDto::from)
                    .toList();
        }
        Page<ChaosInjectionEntity> found = injections.findAllByOrderByInjectedAtDesc(
                PageRequest.of(Math.max(0, page), clampSize(size)));
        return found.getContent().stream().map(ChaosViewDto::from).toList();
    }

    // -------------------------------------------------------------------------------------
    // Replay
    // -------------------------------------------------------------------------------------

    /** Re-consumes a topic from an offset or a timestamp. Synchronous and bounded. */
    @PostMapping("/replay")
    public ReplayResult replay(@RequestBody ReplayRequestDto request) {
        return replayService.replay(request.toRequest());
    }

    // -------------------------------------------------------------------------------------
    // Scenarios
    // -------------------------------------------------------------------------------------

    /** The curated scenarios, each with its declared expected outcome. */
    @GetMapping("/scenarios")
    public List<ScenarioViewDto> scenarios() {
        return ScenarioLibrary.all().stream().map(ScenarioViewDto::from).toList();
    }

    /**
     * Runs one curated scenario.
     *
     * @param key     scenario key from {@code GET /scenarios}
     * @param seed    optional seed override; the scenario's pinned seed is used when absent
     * @param startAt optional world start override. The scenarios pin a fixed instant so runs are
     *     byte-identical; move it forward when the demo needs deadlines that are genuinely ahead
     *     of now (the high-value-urgent-deadline scenario in particular).
     */
    @PostMapping("/scenarios/{key}/run")
    public ResponseEntity<ScenarioRunResponse> runScenario(
            @PathVariable @NotBlank @Size(max = 64) String key,
            @RequestParam(required = false) Long seed,
            @RequestParam(required = false) Instant startAt,
            @RequestParam(required = false) String requestedBy) {

        Scenario scenario = ScenarioLibrary.require(key);
        WorldSpec spec = scenario.spec();
        if (seed != null) {
            spec = spec.withSeed(seed);
        }
        if (startAt != null) {
            spec = spec.withStartAt(startAt);
        }
        SimulationRunEntity run = runner.start(spec,
                requestedBy == null ? "scenario:" + key : requestedBy);
        log.info("scenario {} started as run {}", key, run.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ScenarioRunResponse(
                RunViewDto.from(run, 0L), ScenarioViewDto.from(scenario)));
    }

    /** The run that was started, alongside the expectations it should satisfy. */
    public record ScenarioRunResponse(RunViewDto run, ScenarioViewDto scenario) {
    }

    // -------------------------------------------------------------------------------------

    private static long plannedOf(SimulationRunEntity run) {
        Object planned = run.getStats() == null ? null : run.getStats().get("eventsPlanned");
        return planned instanceof Number number ? number.longValue() : run.getEventsEmitted();
    }

    private static int clampSize(int size) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    }
}
