package com.laserpay.pdei.common.domain;

/**
 * Kinds of evidence PDEI tracks (PLATFORM-CONTRACT section 6).
 *
 * <p>This vocabulary is the join between the policy engine (which requirement types a reason code
 * demands), the evidence engine (what is actually held) and the readiness engine (what is missing).
 * Spelling is identical in Java, Python and TypeScript.
 */
public enum EvidenceType {
    PAYMENT_PROOF,
    INVOICE,
    ORDER_RECORD,
    SHIPPING_RECORD,
    DELIVERY_PROOF,
    REFUND_RECEIPT,
    CUSTOMER_COMMUNICATION,
    MERCHANT_POLICY,
    TERMS_OF_SERVICE,
    AVS_CVV_RESULT,
    DEVICE_FINGERPRINT,
    PRIOR_TRANSACTION_HISTORY,
    SIGNED_CONTRACT
}
