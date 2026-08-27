package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.AuditQueryService;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.core.audit.ChainVerification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/audit} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET /audit?entityId=&amp;entityType=&amp;page=
 * GET /audit/verify-chain?merchantId=
 * </pre>
 *
 * <p>Read only by design, permanently. The audit chain is appended inside the operation being
 * audited; an HTTP route that could write to it would make the chain forgeable and every provenance
 * claim in the platform unfalsifiable.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "audit", description = "Hash-chained audit log")
public class AuditController {

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Audit events",
            description = "Filter by entity (entityType and entityId together) or by merchant with "
                    + "optional actor and time bounds.")
    public PageResponse<AuditEvent> find(
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "entityId", required = false) String entityId,
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return audit.find(entityType, entityId, merchantId, actor, from, to, page, size);
    }

    /**
     * Recompute the merchant's hash chain and report the first entry whose stored hash no longer
     * matches its content.
     *
     * <p>A broken chain is a 200 with {@code intact: false}, not a 5xx. The caller asked a question
     * and this is the answer; dressing a tamper detection up as a server error would make it look
     * like an outage and get it retried instead of investigated.</p>
     */
    @GetMapping("/verify-chain")
    @Operation(summary = "Verify the hash chain of one merchant")
    public ChainVerification verifyChain(@RequestParam(name = "merchantId") String merchantId) {
        return audit.verifyChain(merchantId);
    }
}
