package com.laserpay.pdei.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code GET /api/v1/health/ready}.
 *
 * <p>Separate from {@code /actuator/health}: actuator answers Kubernetes, this answers the frontend,
 * which wants to know which backing services are reachable so it can degrade a panel instead of
 * showing an empty dashboard.</p>
 *
 * @param status      READY when every required dependency is up, DEGRADED when an optional one is
 *                    down, NOT_READY when a required one is
 * @param service     always {@code api-gateway-service}
 * @param dependencies dependency name to UP / DOWN / UNKNOWN
 * @param degraded    dependency names that are down but not fatal
 */
public record HealthResponse(
        String status,
        String service,
        Map<String, String> dependencies,
        List<String> degraded,
        Instant at) {

    public static final String READY = "READY";
    public static final String DEGRADED = "DEGRADED";
    public static final String NOT_READY = "NOT_READY";

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String UNKNOWN = "UNKNOWN";

    public HealthResponse {
        dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
        degraded = degraded == null ? List.of() : List.copyOf(degraded);
    }

    public boolean isReady() {
        return READY.equals(status) || DEGRADED.equals(status);
    }
}
