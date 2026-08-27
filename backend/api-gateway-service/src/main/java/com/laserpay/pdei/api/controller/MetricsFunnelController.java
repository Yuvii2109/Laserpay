package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.FunnelResponse;
import com.laserpay.pdei.api.service.FunnelQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/metrics/funnel} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <p>Events to candidates to ambiguous to AI to human. This is the route that has to show the
 * platform's central claim: the AI layer does not scale with event volume. The numbers come from the
 * admission log and the case tables, not from a counter someone could reset, so the drop-off shown
 * here is the drop-off that actually happened.</p>
 *
 * <p>Distinct from {@code /actuator/prometheus}, which exposes the Micrometer meters for scraping.
 * This one is a product view for the observability screen, aggregated per merchant and per window.</p>
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "metrics", description = "Funnel metrics for the observability screen")
public class MetricsFunnelController {

    private final FunnelQueryService funnel;

    public MetricsFunnelController(FunnelQueryService funnel) {
        this.funnel = funnel;
    }

    @GetMapping("/funnel")
    @Operation(summary = "Events to candidates to ambiguous to AI to human",
            description = "Defaults to the last 7 days. Omit merchantId for platform-wide numbers.")
    public FunnelResponse funnel(
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return funnel.funnel(merchantId, from, to);
    }
}
