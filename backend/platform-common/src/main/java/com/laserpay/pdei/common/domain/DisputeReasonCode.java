package com.laserpay.pdei.common.domain;

/**
 * Normalised dispute reason codes (PLATFORM-CONTRACT section 6).
 *
 * <p>Card-network specific codes are mapped onto this vocabulary at ingestion so that one policy
 * and one requirement matrix serve every acquirer. The reason code selects which evidence
 * requirements apply, which is why readiness is always answered "ready for <em>what</em>".
 */
public enum DisputeReasonCode {
    GOODS_NOT_RECEIVED,
    SERVICE_NOT_RENDERED,
    PRODUCT_NOT_AS_DESCRIBED,
    DUPLICATE_PROCESSING,
    CREDIT_NOT_PROCESSED,
    SUBSCRIPTION_CANCELLED,
    FRAUDULENT_TRANSACTION,
    UNRECOGNIZED_TRANSACTION,
    INCORRECT_AMOUNT,
    PAID_BY_OTHER_MEANS
}
