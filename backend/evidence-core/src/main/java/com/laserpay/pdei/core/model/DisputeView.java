package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/** Immutable read view of a dispute row. */
public record DisputeView(
        String disputeId,
        String merchantId,
        String transactionId,
        DisputeReasonCode reasonCode,
        DisputeStatus status,
        Money amount,
        String networkCaseRef,
        String source,
        Instant openedAt,
        Instant deadlineAt,
        Instant closedAt,
        Instant updatedAt) {

    public boolean isTerminal() {
        return status == DisputeStatus.WON || status == DisputeStatus.LOST
                || status == DisputeStatus.EXPIRED || status == DisputeStatus.WITHDRAWN;
    }

    public boolean pastDeadline(Instant now) {
        return deadlineAt != null && now != null && now.isAfter(deadlineAt);
    }
}
