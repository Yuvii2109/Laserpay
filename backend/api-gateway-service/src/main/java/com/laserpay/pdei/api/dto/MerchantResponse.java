package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.persistence.entity.MerchantEntity;
import java.time.Instant;
import java.util.Map;

/**
 * A merchant as the API exposes it ({@code GET /merchants}, {@code GET /merchants/{merchantId}}).
 *
 * <p>{@code baselineWinRateBps} stays in basis points, the integer form the column stores. Exposing
 * it as a percentage would be a lossy conversion in the one place the frontend is most likely to
 * round it again.</p>
 */
public record MerchantResponse(
        String merchantId,
        String legalName,
        String displayName,
        String country,
        String defaultCurrency,
        String mcc,
        String status,
        String timezone,
        String contactEmail,
        Integer baselineWinRateBps,
        Instant onboardedAt,
        Map<String, Object> riskProfile,
        Instant createdAt,
        Instant updatedAt) {

    public static MerchantResponse from(MerchantEntity entity) {
        return new MerchantResponse(
                entity.getId(),
                entity.getLegalName(),
                entity.getDisplayName(),
                entity.getCountry(),
                entity.getDefaultCurrency(),
                entity.getMcc(),
                entity.getStatus(),
                entity.getTimezone(),
                entity.getContactEmail(),
                entity.getBaselineWinRateBps(),
                entity.getOnboardedAt(),
                entity.getRiskProfile(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
