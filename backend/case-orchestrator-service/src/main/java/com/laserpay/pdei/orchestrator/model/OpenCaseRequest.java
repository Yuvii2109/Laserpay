package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/**
 * Argument of activity 1, {@code openCase}.
 *
 * <p>{@code caseId} is chosen by the caller and is derived deterministically from the dispute id
 * (see {@code listener.CaseIdResolver}), which is what makes a redelivered {@code DisputeCreated}
 * land on the same workflow id and the same case row instead of opening a second case.</p>
 */
public record OpenCaseRequest(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        Money disputeAmount,
        Instant openedAt,
        Instant deadlineAt,
        String workflowId,
        String runId,
        String correlationId,
        String actor) {
}
