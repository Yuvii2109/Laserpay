package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RecommendedAction;

import java.util.List;

/**
 * A curated demo scenario: a fixed {@link WorldSpec} plus the outcome it is supposed to produce.
 *
 * <p>The expectations are the point. A scenario that only generated data would be a fixture; one
 * that also declares "this must land in AT_RISK with a CONTRADICTORY gap and go to the model" is
 * an executable claim about the deterministic engine. When readiness scoring, gap detection or
 * admission control drifts, the scenario stops matching and someone finds out before the demo
 * does.
 *
 * <p>Every scenario pins its own seed and sets {@code disputeRateBps = 10000}, so every generated
 * transaction ends in a dispute of the declared reason code and the run is the same every time.
 *
 * @param key                     stable URL key: {@code POST /sim/v1/scenarios/{key}/run}
 * @param title                   short human-readable name for the console
 * @param description             what the scenario demonstrates
 * @param reasonCode              dispute reason code every generated dispute uses
 * @param spec                    the world to generate
 * @param expectedBand            readiness band the disputed transactions should land in
 * @param expectedScoreMin        inclusive lower bound of the expected readiness score
 * @param expectedScoreMax        inclusive upper bound of the expected readiness score
 * @param expectedGaps            gap types the detector should raise
 * @param expectedAiPath          whether this should reach the reasoning service at all
 * @param expectedClassification  classification the investigation should reach
 * @param expectedAction          recommended action the safety gate should permit
 * @param demoNote                one line for the operator running the demo
 */
public record Scenario(String key,
                       String title,
                       String description,
                       DisputeReasonCode reasonCode,
                       WorldSpec spec,
                       ReadinessBand expectedBand,
                       int expectedScoreMin,
                       int expectedScoreMax,
                       List<GapType> expectedGaps,
                       AiPath expectedAiPath,
                       InvestigationClassification expectedClassification,
                       RecommendedAction expectedAction,
                       String demoNote) {

    public Scenario {
        expectedGaps = expectedGaps == null ? List.of() : List.copyOf(expectedGaps);
    }

    /** The scenario's spec re-seeded, for "run it again but different" without losing the shape. */
    public Scenario withSeed(long seed) {
        return new Scenario(key, title, description, reasonCode, spec.withSeed(seed), expectedBand,
                expectedScoreMin, expectedScoreMax, expectedGaps, expectedAiPath,
                expectedClassification, expectedAction, demoNote);
    }
}
