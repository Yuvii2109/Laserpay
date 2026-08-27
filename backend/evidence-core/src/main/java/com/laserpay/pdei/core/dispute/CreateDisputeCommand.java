package com.laserpay.pdei.core.dispute;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/**
 * Request to open a dispute, from a PSP webhook, the merchant portal, the simulator
 * ({@code ChaosType.INJECT_DISPUTE}) or {@code POST /api/v1/disputes}.
 *
 * @param deadlineAt explicit network deadline; when null the policy response window is applied
 */
public record CreateDisputeCommand(
        String merchantId,
        String transactionId,
        DisputeReasonCode reasonCode,
        Money amount,
        String networkCaseRef,
        String source,
        Instant openedAt,
        Instant deadlineAt,
        String correlationId,
        String sourceEventId,
        String actor) {

    public static CreateDisputeCommand of(String merchantId, String transactionId,
                                          DisputeReasonCode reasonCode, Money amount, String source) {
        return new CreateDisputeCommand(merchantId, transactionId, reasonCode, amount, null, source,
                null, null, null, null, "SYSTEM");
    }
}
