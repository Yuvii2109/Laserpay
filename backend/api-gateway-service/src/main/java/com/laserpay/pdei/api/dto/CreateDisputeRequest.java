package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.dispute.CreateDisputeCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * {@code POST /disputes}: manual or injected dispute creation.
 *
 * <p>The amount arrives as {@code (amountMinor, currency)} and nothing else. There is no
 * {@code amount} decimal field, on purpose and permanently: a JSON number for money is a rounding
 * bug waiting for the first three-decimal currency.</p>
 *
 * @param deadlineAt optional; when absent the policy response window decides
 */
public record CreateDisputeRequest(
        @NotBlank(message = "merchantId is required")
        @Pattern(regexp = "^MER-[A-Za-z0-9_-]+$", message = "must be a MER- prefixed id")
        String merchantId,

        @NotBlank(message = "transactionId is required")
        @Pattern(regexp = "^TX-[A-Za-z0-9_-]+$", message = "must be a TX- prefixed id")
        String transactionId,

        @NotNull(message = "reasonCode is required")
        DisputeReasonCode reasonCode,

        @Positive(message = "amountMinor must be positive")
        long amountMinor,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency,

        String networkCaseRef,

        String source,

        Instant openedAt,

        Instant deadlineAt,

        String actor) {

    public Money amount() {
        return Money.of(amountMinor, currency == null ? null : currency.toUpperCase(java.util.Locale.ROOT));
    }

    public CreateDisputeCommand toCommand(String correlationId) {
        return new CreateDisputeCommand(
                merchantId,
                transactionId,
                reasonCode,
                amount(),
                networkCaseRef,
                source == null || source.isBlank() ? "MERCHANT_PORTAL" : source,
                openedAt,
                deadlineAt,
                correlationId,
                null,
                actor == null || actor.isBlank() ? "MERCHANT_PORTAL" : actor);
    }
}
