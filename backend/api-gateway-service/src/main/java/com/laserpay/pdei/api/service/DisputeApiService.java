package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.CreateDisputeRequest;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.support.CorrelationIds;
import com.laserpay.pdei.api.support.Paging;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.core.dispute.DisputeService;
import com.laserpay.pdei.core.model.DisputeView;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /disputes} routes.
 *
 * <p>Creation is delegated whole to {@code DisputeService.create}, which is idempotent per open
 * transaction: a second POST for a transaction that already has an open dispute returns the existing
 * one instead of opening a rival. That matters because this route is also how the simulator injects
 * disputes, and injection is replayed.</p>
 */
@Service
public class DisputeApiService {

    private final DisputeService disputeService;

    public DisputeApiService(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    /** {@code GET /disputes?merchantId&status&reasonCode}. */
    @Transactional(readOnly = true)
    public PageResponse<DisputeView> list(String merchantId, DisputeStatus status,
                                          DisputeReasonCode reasonCode, int page, int size) {
        // Validated before the port sees them: a negative page becomes a negative OFFSET in SQL.
        int safePage = Paging.page(page);
        int safeSize = Paging.size(size, Paging.MAX_SIZE);
        List<DisputeView> slice =
                disputeService.list(blankToNull(merchantId), status, reasonCode, safePage, safeSize);
        return PageResponse.ofSlice(slice, safePage, safeSize);
    }

    /** {@code GET /disputes/{disputeId}}. 404 when unknown. */
    @Transactional(readOnly = true)
    public DisputeView get(String disputeId) {
        return disputeService.require(disputeId);
    }

    /** {@code POST /disputes}: manual or injected creation. */
    @Transactional
    public DisputeView create(CreateDisputeRequest request) {
        return disputeService.create(request.toCommand(CorrelationIds.currentOrNull()));
    }

    /** Legal next statuses, so the UI renders only the actions that would actually be accepted. */
    @Transactional(readOnly = true)
    public List<DisputeStatus> allowedTransitions(String disputeId) {
        DisputeView dispute = disputeService.require(disputeId);
        return disputeService.allowedTransitions(dispute.status()).stream().sorted().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
