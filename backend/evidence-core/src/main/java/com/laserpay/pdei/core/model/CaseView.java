package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.CaseStatus;

import java.time.Instant;

/** Immutable read view of a dispute case row (the Temporal-driven workflow aggregate). */
public record CaseView(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        CaseStatus status,
        String workflowId,
        String assignedTo,
        int packageVersion,
        Instant openedAt,
        Instant updatedAt,
        Instant closedAt) {
}
