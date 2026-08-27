package com.laserpay.pdei.core.ai;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/**
 * Everything the priority formula of platform contract 9.4 needs. All of it is deterministic state
 * that the platform already computed - nothing here comes from a model.
 */
public record AdmissionRequest(
        String caseId,
        String merchantId,
        String transactionId,
        DisputeReasonCode reasonCode,
        Money disputeAmount,
        Instant deadlineAt,
        int contradictionCount,
        int gapCount,
        int evidenceCount,
        int unsatisfiedMandatoryCount,
        double deterministicConfidence,
        Instant now) {

    public boolean allMandatorySatisfied() {
        return unsatisfiedMandatoryCount <= 0;
    }

    public boolean pastDeadline() {
        return deadlineAt != null && now != null && now.isAfter(deadlineAt);
    }

    public long amountMinor() {
        return disputeAmount == null ? 0L : disputeAmount.amountMinor();
    }
}
