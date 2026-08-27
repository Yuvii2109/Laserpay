package com.laserpay.pdei.core.model;

/**
 * The {@code modelMetadata} object of {@link InvestigationResult} (platform contract 9.2).
 * Field names are exactly {@code provider}, {@code model}, {@code promptTokens},
 * {@code completionTokens}, {@code latencyMs}, {@code attempt}.
 */
public record ModelMetadata(
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        int attempt) {

    /** Marker used when the deterministic fallback produced the result instead of the AI service. */
    public static final String DETERMINISTIC_PROVIDER = "deterministic";
    public static final String DETERMINISTIC_MODEL = "pdei-deterministic-v1";

    public static ModelMetadata deterministic(long latencyMs, int attempt) {
        return new ModelMetadata(DETERMINISTIC_PROVIDER, DETERMINISTIC_MODEL, 0, 0, latencyMs, attempt);
    }

    public boolean isDeterministic() {
        return DETERMINISTIC_PROVIDER.equals(provider);
    }
}
