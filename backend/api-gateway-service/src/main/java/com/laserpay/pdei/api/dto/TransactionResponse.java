package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import java.time.Instant;
import java.util.Map;

/**
 * A transaction row ({@code GET /transactions}, {@code GET /transactions/{transactionId}}).
 *
 * <p>All three amounts are {@link Money}, which serialises as
 * {@code {"amountMinor": 1299900, "currency": "INR"}}. Minor units all the way to the browser:
 * formatting happens at render time and nowhere else.</p>
 */
public record TransactionResponse(
        String transactionId,
        String merchantId,
        String customerId,
        String externalRef,
        Money amount,
        Money capturedAmount,
        Money refundedAmount,
        String status,
        String channel,
        Integer readinessScore,
        ReadinessBand readinessBand,
        Instant readinessComputedAt,
        Instant occurredAt,
        Instant observedAt,
        String lastEventId,
        Instant lastEventAt,
        Map<String, Object> metadata) {

    public static TransactionResponse from(TransactionEntity entity) {
        return new TransactionResponse(
                entity.getId(),
                entity.getMerchantId(),
                entity.getCustomerId(),
                entity.getExternalRef(),
                entity.getAmountAsMoney(),
                entity.getCapturedAmountAsMoney(),
                entity.getRefundedAmountAsMoney(),
                entity.getStatus(),
                entity.getChannel(),
                entity.getReadinessScore(),
                entity.getReadinessBand(),
                entity.getReadinessComputedAt(),
                entity.getOccurredAt(),
                entity.getObservedAt(),
                entity.getLastEventId(),
                entity.getLastEventAt(),
                entity.getMetadata());
    }
}
