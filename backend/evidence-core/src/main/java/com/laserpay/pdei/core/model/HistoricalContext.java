package com.laserpay.pdei.core.model;

/**
 * The {@code historicalContext} object of {@link InvestigationContext} (platform contract 9.1).
 * Field names are exactly {@code merchantWinRate}, {@code similarCases}.
 */
public record HistoricalContext(double merchantWinRate, int similarCases) {

    public static HistoricalContext unknown() {
        return new HistoricalContext(0.0d, 0);
    }
}
