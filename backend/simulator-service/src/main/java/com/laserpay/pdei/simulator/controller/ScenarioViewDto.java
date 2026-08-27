package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.simulator.world.Scenario;

import java.util.List;

/**
 * A curated scenario as {@code GET /sim/v1/scenarios} renders it.
 *
 * <p>The expectations travel with the scenario deliberately. The simulation console shows what
 * <em>should</em> happen next to what <em>did</em>, which turns the demo into a check rather than
 * a narration: if the readiness band or the AI path diverges from what is declared here, the
 * deterministic engine has drifted and the screen says so.
 */
public record ScenarioViewDto(String key,
                              String title,
                              String description,
                              String reasonCode,
                              long seed,
                              int merchants,
                              int transactions,
                              int days,
                              String startAt,
                              Expected expected,
                              String demoNote) {

    /** The declared outcome of a scenario. */
    public record Expected(String readinessBand,
                           int scoreMin,
                           int scoreMax,
                           List<String> gapTypes,
                           String aiPath,
                           String classification,
                           String recommendedAction) {
    }

    public static ScenarioViewDto from(Scenario scenario) {
        return new ScenarioViewDto(
                scenario.key(),
                scenario.title(),
                scenario.description(),
                scenario.reasonCode().name(),
                scenario.spec().seed(),
                scenario.spec().merchants(),
                scenario.spec().transactions(),
                scenario.spec().days(),
                scenario.spec().startAt().toString(),
                new Expected(
                        scenario.expectedBand().name(),
                        scenario.expectedScoreMin(),
                        scenario.expectedScoreMax(),
                        scenario.expectedGaps().stream().map(Enum::name).toList(),
                        scenario.expectedAiPath().name(),
                        scenario.expectedClassification().name(),
                        scenario.expectedAction().name()),
                scenario.demoNote());
    }
}
