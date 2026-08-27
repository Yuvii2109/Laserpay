package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.FunnelMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /metrics/funnel}: events to candidates to ambiguous to AI to human.
 *
 * <p>The point of this route is the shape of the drop-off. Reference section 29 asks for a funnel
 * that narrows sharply, because the whole cost argument rests on the AI layer <em>not</em> scaling
 * with event volume. {@code stages} is the same numbers already differenced and ratioed so the chart
 * cannot compute the conversion differently from the API.</p>
 *
 * <p>{@code conversionFromPrevious} is a ratio in [0,1] and a double. That is fine: it is a
 * dimensionless proportion, not money. Nothing in this response is a monetary amount.</p>
 */
public record FunnelResponse(
        FunnelMetrics metrics,
        List<Stage> stages,
        double aiAdmissionRate,
        double autoPrepareRate) {

    public FunnelResponse {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /**
     * @param count                  absolute count reaching this stage
     * @param conversionFromPrevious count divided by the previous stage's count, 0 when undefined
     */
    public record Stage(String name, long count, double conversionFromPrevious) {
    }

    public static FunnelResponse from(FunnelMetrics metrics) {
        List<Stage> stages = new ArrayList<>();
        long previous = -1L;
        previous = append(stages, "events", metrics.events(), previous);
        previous = append(stages, "candidates", metrics.candidates(), previous);
        previous = append(stages, "ambiguous", metrics.ambiguous(), previous);
        previous = append(stages, "aiInvestigated", metrics.aiInvestigated(), previous);
        append(stages, "humanReviewed", metrics.humanReviewed(), previous);
        // Terminal outcomes hang off candidates rather than off the previous stage: a case can be
        // auto-prepared without ever reaching the model, so chaining them would misreport conversion.
        stages.add(new Stage("autoPrepared", metrics.autoPrepared(),
                ratio(metrics.autoPrepared(), metrics.candidates())));
        stages.add(new Stage("denied", metrics.denied(), ratio(metrics.denied(), metrics.candidates())));
        return new FunnelResponse(metrics, stages, metrics.aiAdmissionRate(), metrics.autoPrepareRate());
    }

    private static long append(List<Stage> stages, String name, long count, long previous) {
        stages.add(new Stage(name, count, previous < 0 ? 1.0d : ratio(count, previous)));
        return count;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0d : numerator / (double) denominator;
    }
}
