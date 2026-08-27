package com.laserpay.pdei.orchestrator.persistence;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/**
 * A row of {@code pdei.dispute_cases} as the orchestrator needs to see it.
 *
 * <p>Deliberately narrower than {@code core.model.CaseView}: the orchestrator writes and re-reads
 * the workflow-owned columns ({@code status}, {@code progress_percent}, {@code workflow_id},
 * {@code run_id}, the approval and submission timestamps), while everything else about a case is
 * read through {@code evidence-core}.</p>
 */
public record CaseRow(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        CaseStatus status,
        Money amount,
        String workflowId,
        String runId,
        int packageVersion,
        int progressPercent,
        Instant openedAt,
        Instant deadlineAt,
        Instant closedAt) {
}
