package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/**
 * Result of activity 1, {@code openCase}.
 *
 * @param alreadyOpen true when a case row for this dispute already existed. The activity is
 *                    idempotent, so this is the normal outcome of a retry or of a duplicate
 *                    {@code DisputeCreated} delivery - never an error.
 */
public record OpenCaseResult(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        CaseStatus caseStatus,
        DisputeStatus disputeStatus,
        DisputeReasonCode reasonCode,
        Money disputeAmount,
        Instant openedAt,
        Instant deadlineAt,
        boolean alreadyOpen) {
}
