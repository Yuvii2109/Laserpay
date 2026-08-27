package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.MerchantResponse;
import com.laserpay.pdei.api.dto.MerchantSummaryResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.MerchantQueryService;
import com.laserpay.pdei.api.support.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/merchants} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET /merchants                       list (page, size)
 * GET /merchants/{merchantId}
 * GET /merchants/{merchantId}/summary  control-tower KPIs
 * </pre>
 *
 * <p>Read only. Merchants are created by ingestion and the simulator, never through this API.</p>
 */
@RestController
@RequestMapping("/api/v1/merchants")
@Tag(name = "merchants", description = "Merchant directory and control-tower KPIs")
public class MerchantController {

    private final MerchantQueryService merchants;
    private final ApiProperties properties;

    public MerchantController(MerchantQueryService merchants, ApiProperties properties) {
        this.merchants = merchants;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "List merchants")
    public PageResponse<MerchantResponse> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        return merchants.list(Paging.of(page, size, properties.getPaging().getMaxSize(),
                Sort.by(Sort.Direction.ASC, "id")));
    }

    @GetMapping("/{merchantId}")
    @Operation(summary = "One merchant")
    public MerchantResponse get(@PathVariable("merchantId") String merchantId) {
        return merchants.get(merchantId);
    }

    @GetMapping("/{merchantId}/summary")
    @Operation(summary = "Control-tower KPIs",
            description = "Readiness distribution, open disputes, at-risk transactions, expiring "
                    + "evidence and the human review queue depth. Counts only: no aggregated money.")
    public MerchantSummaryResponse summary(@PathVariable("merchantId") String merchantId) {
        return merchants.summary(merchantId);
    }
}
