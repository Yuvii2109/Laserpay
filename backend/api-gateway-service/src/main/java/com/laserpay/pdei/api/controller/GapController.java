package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.GapQueryService;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.core.model.ReadinessGap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/gaps?merchantId&type&severity} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <p>The at-risk feed on the control tower. Only unresolved gaps are returned, ordered by severity
 * descending, because the feed's job is to open on what is actually blocking a representment.</p>
 */
@RestController
@RequestMapping("/api/v1/gaps")
@Tag(name = "gaps", description = "At-risk evidence gap feed")
public class GapController {

    private final GapQueryService gaps;

    public GapController(GapQueryService gaps) {
        this.gaps = gaps;
    }

    @GetMapping
    @Operation(summary = "Unresolved evidence gaps for a merchant",
            description = "Ordered CRITICAL, HIGH, MEDIUM, LOW, then most recently detected first.")
    public PageResponse<ReadinessGap> find(
            @RequestParam(name = "merchantId") String merchantId,
            @RequestParam(name = "type", required = false) GapType type,
            @RequestParam(name = "severity", required = false) GapSeverity severity,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        return gaps.find(merchantId, type, severity, page, size);
    }

    @GetMapping("/transaction/{transactionId}")
    @Operation(summary = "Unresolved gaps of one transaction",
            description = "The drill-down from a row in the at-risk feed.")
    public List<ReadinessGap> forTransaction(@PathVariable("transactionId") String transactionId) {
        return gaps.forTransaction(transactionId);
    }
}
