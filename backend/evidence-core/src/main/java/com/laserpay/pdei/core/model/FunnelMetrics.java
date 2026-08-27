package com.laserpay.pdei.core.model;

import java.time.Instant;

/**
 * The events -> candidates -> ambiguous -> AI -> human funnel behind
 * {@code GET /api/v1/metrics/funnel} and the {@code /observability} screen.
 *
 * <p>This is the number that proves the economic thesis: only ambiguous cases should reach the
 * model, so {@code aiInvestigated} must stay a small fraction of {@code candidates}.</p>
 */
public record FunnelMetrics(
        String merchantId,
        Instant from,
        Instant to,
        long events,
        long candidates,
        long ambiguous,
        long aiInvestigated,
        long humanReviewed,
        long autoPrepared,
        long denied) {

    /** Share of candidate cases that were actually sent to the model, in [0,1]. */
    public double aiAdmissionRate() {
        return candidates <= 0 ? 0.0d : aiInvestigated / (double) candidates;
    }

    /** Share of candidate cases resolved with no human touch, in [0,1]. */
    public double autoPrepareRate() {
        return candidates <= 0 ? 0.0d : autoPrepared / (double) candidates;
    }

    public static FunnelMetrics zero(String merchantId, Instant from, Instant to) {
        return new FunnelMetrics(merchantId, from, to, 0, 0, 0, 0, 0, 0, 0);
    }
}
