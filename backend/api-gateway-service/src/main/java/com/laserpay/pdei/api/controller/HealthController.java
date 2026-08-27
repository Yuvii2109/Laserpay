package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.HealthResponse;
import com.laserpay.pdei.api.service.ReadinessProbeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/health/ready} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <p>The frontend's own health probe, distinct from {@code /actuator/health}, which is what
 * Kubernetes reads. This one names each backing service so the UI can hide the panels it cannot
 * populate rather than rendering them empty.</p>
 *
 * <p>503 only when Postgres is unreachable, because that is the only dependency without which no
 * route works. A degraded gateway still answers 200: Redis, Kafka or MinIO being down each disable
 * one capability, not the API.</p>
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "health", description = "Frontend readiness probe")
public class HealthController {

    private final ReadinessProbeService probeService;

    public HealthController(ReadinessProbeService probeService) {
        this.probeService = probeService;
    }

    @GetMapping("/ready")
    @Operation(summary = "Readiness of the gateway and its dependencies",
            description = "200 when the API can serve requests (possibly degraded), 503 when it cannot.")
    public ResponseEntity<HealthResponse> ready() {
        HealthResponse health = probeService.probe();
        return ResponseEntity
                .status(health.isReady() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(health);
    }
}
