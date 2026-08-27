package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.CreateDisputeRequest;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.DisputeApiService;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.core.model.DisputeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/disputes} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET  /disputes              ?merchantId&amp;status&amp;reasonCode
 * GET  /disputes/{disputeId}
 * POST /disputes              manual or injected creation
 * </pre>
 *
 * <p>The dispute amount is {@code (amountMinor, currency)} in both directions. There is no decimal
 * representation of money anywhere on this route.</p>
 */
@RestController
@RequestMapping("/api/v1/disputes")
@Tag(name = "disputes", description = "Dispute lifecycle")
public class DisputeController {

    private final DisputeApiService disputes;

    public DisputeController(DisputeApiService disputes) {
        this.disputes = disputes;
    }

    @GetMapping
    @Operation(summary = "List disputes")
    public PageResponse<DisputeView> list(
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "status", required = false) DisputeStatus status,
            @RequestParam(name = "reasonCode", required = false) DisputeReasonCode reasonCode,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        return disputes.list(merchantId, status, reasonCode, page, size);
    }

    @GetMapping("/{disputeId}")
    @Operation(summary = "One dispute")
    public DisputeView get(@PathVariable("disputeId") String disputeId) {
        return disputes.get(disputeId);
    }

    /**
     * Idempotent per transaction: a second POST for a transaction that already has an open dispute
     * returns the existing one instead of opening a rival, which is what makes this route safe for
     * the simulator to replay.
     */
    @PostMapping
    @Operation(summary = "Create a dispute",
            description = "Idempotent per transaction. amountMinor and currency only: no decimals.")
    public ResponseEntity<DisputeView> create(@Valid @RequestBody CreateDisputeRequest request) {
        DisputeView created = disputes.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/disputes/" + created.disputeId()))
                .body(created);
    }

    @GetMapping("/{disputeId}/transitions")
    @Operation(summary = "Legal next statuses",
            description = "So the UI renders only the actions the state machine would accept.")
    public List<DisputeStatus> transitions(@PathVariable("disputeId") String disputeId) {
        return disputes.allowedTransitions(disputeId);
    }
}
