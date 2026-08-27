package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.dto.TimelineResponse;
import com.laserpay.pdei.api.dto.TransactionDetailResponse;
import com.laserpay.pdei.api.dto.TransactionResponse;
import com.laserpay.pdei.api.service.TransactionQueryService;
import com.laserpay.pdei.api.support.Paging;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.core.model.EvidenceGraph;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/transactions} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET  /transactions                                ?merchantId&amp;band&amp;from&amp;to&amp;page&amp;size
 * GET  /transactions/{transactionId}
 * GET  /transactions/{transactionId}/timeline
 * GET  /transactions/{transactionId}/readiness
 * POST /transactions/{transactionId}/readiness/recompute
 * GET  /transactions/{transactionId}/evidence
 * GET  /transactions/{transactionId}/graph
 * </pre>
 *
 * <p>Recompute is the only non-GET here, and it is a POST rather than a GET on purpose even though
 * the computation is deterministic: it writes a snapshot, and a route that writes must not be
 * reachable by a link, a prefetch or a browser's back button.</p>
 *
 * <p>{@code from} and {@code to} are ISO-8601 instants; the window is half-open, {@code [from, to)},
 * so consecutive days do not double-count the boundary.</p>
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "transactions", description = "Transactions, timeline, readiness and evidence graph")
public class TransactionController {

    private final TransactionQueryService transactions;
    private final ApiProperties properties;

    public TransactionController(TransactionQueryService transactions, ApiProperties properties) {
        this.transactions = transactions;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "Search transactions",
            description = "All filters are optional. Amounts are minor units with an ISO currency.")
    public PageResponse<TransactionResponse> search(
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "band", required = false) ReadinessBand band,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        return transactions.search(merchantId, band, from, to,
                Paging.of(page, size, properties.getPaging().getMaxSize(),
                        Sort.by(Sort.Direction.DESC, "occurredAt")));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "One transaction with its facts, readiness and evidence summary")
    public TransactionDetailResponse get(@PathVariable("transactionId") String transactionId) {
        return transactions.get(transactionId);
    }

    @GetMapping("/{transactionId}/timeline")
    @Operation(summary = "Unified event and evidence timeline",
            description = "Ordered by when things happened, not by when the platform observed them.")
    public TimelineResponse timeline(@PathVariable("transactionId") String transactionId) {
        return transactions.timeline(transactionId);
    }

    @GetMapping("/{transactionId}/readiness")
    @Operation(summary = "Current readiness snapshot",
            description = "The stored snapshot when it matches the requested reason code, otherwise "
                    + "a fresh deterministic computation.")
    public ReadinessSnapshot readiness(
            @PathVariable("transactionId") String transactionId,
            @RequestParam(name = "reasonCode", required = false) DisputeReasonCode reasonCode) {
        return transactions.readiness(transactionId, reasonCode);
    }

    @PostMapping("/{transactionId}/readiness/recompute")
    @Operation(summary = "Recompute and persist readiness",
            description = "Deterministic and idempotent: gap ids are a hash of their content, so a "
                    + "repeat run upserts rather than accumulating duplicates.")
    public ReadinessSnapshot recompute(
            @PathVariable("transactionId") String transactionId,
            @RequestParam(name = "reasonCode", required = false) DisputeReasonCode reasonCode) {
        return transactions.recompute(transactionId, reasonCode);
    }

    @GetMapping("/{transactionId}/evidence")
    @Operation(summary = "Evidence linked to this transaction")
    public List<EvidenceView> evidence(@PathVariable("transactionId") String transactionId) {
        return transactions.evidence(transactionId);
    }

    @GetMapping("/{transactionId}/graph")
    @Operation(summary = "Evidence graph",
            description = "Nodes and edges including CONTRADICTS edges between conflicting records.")
    public EvidenceGraph graph(@PathVariable("transactionId") String transactionId) {
        return transactions.graph(transactionId);
    }
}
