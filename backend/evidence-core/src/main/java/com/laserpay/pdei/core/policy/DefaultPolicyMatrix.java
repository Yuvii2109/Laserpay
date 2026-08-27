package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.RequirementStrength;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The seeded platform default policy: what evidence each of the ten {@link DisputeReasonCode}
 * values requires, how long each evidence type stays fresh, and the default automation thresholds.
 *
 * <p>This matrix is the fallback whenever a merchant has not published a policy of their own. It is
 * pure data and deterministic - two calls with the same arguments return the same matrix, so a
 * readiness score computed against the default policy is reproducible.</p>
 *
 * <p>Prohibited evidence types are empty by default: prohibition is a merchant or regional decision
 * (for example a merchant that must not attach raw device fingerprints to a network submission), so
 * it is expressed in a published merchant policy rather than assumed here.</p>
 */
public final class DefaultPolicyMatrix {

    /** Default automation thresholds (platform contract 9.1 {@code policyConstraints}). */
    public static final double DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE = 0.90d;
    public static final int DEFAULT_MAX_CONTRADICTIONS = 0;
    public static final int DEFAULT_MIN_READINESS_FOR_AUTO_PREPARE = 75;
    /** Above this dispute value a human always reviews, whatever the model says. INR 50,000.00. */
    public static final long DEFAULT_HUMAN_REVIEW_ABOVE_AMOUNT_MINOR = 5_000_000L;
    public static final String DEFAULT_CURRENCY = "INR";
    public static final boolean DEFAULT_AUTO_SUBMIT_ENABLED = false;
    public static final int DEFAULT_RESPONSE_WINDOW_DAYS = 21;
    /** Contract 7: "expiry within 7 days" is the EXPIRING_SOON window. */
    public static final int DEFAULT_EXPIRING_SOON_DAYS = 7;

    /** Every action is proposable by default; merchants narrow this, never widen it at runtime. */
    public static final Set<RecommendedAction> DEFAULT_PERMITTED_ACTIONS =
            EnumSet.allOf(RecommendedAction.class);

    /** Reason codes used for the baseline profile when a merchant has no dispute history yet. */
    public static final List<DisputeReasonCode> DEFAULT_TOP_REASON_CODES = List.of(
            DisputeReasonCode.GOODS_NOT_RECEIVED,
            DisputeReasonCode.FRAUDULENT_TRANSACTION,
            DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED);

    private static final Map<EvidenceType, Integer> MAX_AGE_DAYS = new EnumMap<>(EvidenceType.class);
    private static final Map<DisputeReasonCode, List<RequirementSpec>> MATRIX =
            new EnumMap<>(DisputeReasonCode.class);

    private DefaultPolicyMatrix() {
    }

    static {
        // Expiry rules. Financial records are kept for the statutory retention window; attestations
        // and risk signals go stale quickly and must be re-captured.
        MAX_AGE_DAYS.put(EvidenceType.PAYMENT_PROOF, 3650);
        MAX_AGE_DAYS.put(EvidenceType.INVOICE, 3650);
        MAX_AGE_DAYS.put(EvidenceType.ORDER_RECORD, 3650);
        MAX_AGE_DAYS.put(EvidenceType.REFUND_RECEIPT, 3650);
        MAX_AGE_DAYS.put(EvidenceType.SIGNED_CONTRACT, 3650);
        MAX_AGE_DAYS.put(EvidenceType.SHIPPING_RECORD, 540);
        MAX_AGE_DAYS.put(EvidenceType.DELIVERY_PROOF, 540);
        MAX_AGE_DAYS.put(EvidenceType.CUSTOMER_COMMUNICATION, 730);
        MAX_AGE_DAYS.put(EvidenceType.PRIOR_TRANSACTION_HISTORY, 730);
        MAX_AGE_DAYS.put(EvidenceType.MERCHANT_POLICY, 365);
        MAX_AGE_DAYS.put(EvidenceType.TERMS_OF_SERVICE, 365);
        MAX_AGE_DAYS.put(EvidenceType.AVS_CVV_RESULT, 180);
        MAX_AGE_DAYS.put(EvidenceType.DEVICE_FINGERPRINT, 180);

        MATRIX.put(DisputeReasonCode.GOODS_NOT_RECEIVED, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.SHIPPING_RECORD),
                mandatory(EvidenceType.DELIVERY_PROOF),
                mandatory(EvidenceType.MERCHANT_POLICY),
                recommended(EvidenceType.ORDER_RECORD),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                optional(EvidenceType.TERMS_OF_SERVICE),
                optional(EvidenceType.PRIOR_TRANSACTION_HISTORY)));

        MATRIX.put(DisputeReasonCode.SERVICE_NOT_RENDERED, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.SIGNED_CONTRACT),
                mandatory(EvidenceType.MERCHANT_POLICY),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                recommended(EvidenceType.ORDER_RECORD),
                recommended(EvidenceType.TERMS_OF_SERVICE),
                optional(EvidenceType.PRIOR_TRANSACTION_HISTORY)));

        MATRIX.put(DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.ORDER_RECORD),
                mandatory(EvidenceType.DELIVERY_PROOF),
                mandatory(EvidenceType.MERCHANT_POLICY),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                recommended(EvidenceType.TERMS_OF_SERVICE),
                recommended(EvidenceType.SHIPPING_RECORD),
                optional(EvidenceType.PRIOR_TRANSACTION_HISTORY)));

        MATRIX.put(DisputeReasonCode.DUPLICATE_PROCESSING, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.PRIOR_TRANSACTION_HISTORY),
                recommended(EvidenceType.ORDER_RECORD),
                recommended(EvidenceType.REFUND_RECEIPT),
                optional(EvidenceType.CUSTOMER_COMMUNICATION)));

        MATRIX.put(DisputeReasonCode.CREDIT_NOT_PROCESSED, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.REFUND_RECEIPT),
                mandatory(EvidenceType.MERCHANT_POLICY),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                recommended(EvidenceType.INVOICE),
                optional(EvidenceType.ORDER_RECORD),
                optional(EvidenceType.TERMS_OF_SERVICE)));
    }

    static {
        MATRIX.put(DisputeReasonCode.SUBSCRIPTION_CANCELLED, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.TERMS_OF_SERVICE),
                mandatory(EvidenceType.CUSTOMER_COMMUNICATION),
                mandatory(EvidenceType.MERCHANT_POLICY),
                recommended(EvidenceType.INVOICE),
                recommended(EvidenceType.SIGNED_CONTRACT),
                optional(EvidenceType.PRIOR_TRANSACTION_HISTORY)));

        MATRIX.put(DisputeReasonCode.FRAUDULENT_TRANSACTION, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.AVS_CVV_RESULT),
                mandatory(EvidenceType.DEVICE_FINGERPRINT),
                mandatory(EvidenceType.DELIVERY_PROOF),
                recommended(EvidenceType.PRIOR_TRANSACTION_HISTORY),
                recommended(EvidenceType.SHIPPING_RECORD),
                recommended(EvidenceType.ORDER_RECORD),
                optional(EvidenceType.CUSTOMER_COMMUNICATION)));

        MATRIX.put(DisputeReasonCode.UNRECOGNIZED_TRANSACTION, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.ORDER_RECORD),
                recommended(EvidenceType.PRIOR_TRANSACTION_HISTORY),
                recommended(EvidenceType.DEVICE_FINGERPRINT),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                recommended(EvidenceType.AVS_CVV_RESULT),
                optional(EvidenceType.DELIVERY_PROOF)));

        MATRIX.put(DisputeReasonCode.INCORRECT_AMOUNT, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.ORDER_RECORD),
                recommended(EvidenceType.MERCHANT_POLICY),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                recommended(EvidenceType.REFUND_RECEIPT),
                optional(EvidenceType.TERMS_OF_SERVICE)));

        MATRIX.put(DisputeReasonCode.PAID_BY_OTHER_MEANS, specs(
                mandatory(EvidenceType.PAYMENT_PROOF),
                mandatory(EvidenceType.INVOICE),
                mandatory(EvidenceType.PRIOR_TRANSACTION_HISTORY),
                recommended(EvidenceType.ORDER_RECORD),
                recommended(EvidenceType.CUSTOMER_COMMUNICATION),
                optional(EvidenceType.REFUND_RECEIPT)));
    }

    /** Requirements for one reason code. Never null: an unmapped code falls back to the baseline. */
    public static List<RequirementSpec> requirements(DisputeReasonCode reasonCode) {
        if (reasonCode == null) {
            return baselineRequirements(DEFAULT_TOP_REASON_CODES);
        }
        List<RequirementSpec> specs = MATRIX.get(reasonCode);
        return specs == null ? baselineRequirements(DEFAULT_TOP_REASON_CODES) : specs;
    }

    /**
     * The baseline requirement profile of contract 7: the union of the MANDATORY requirements of the
     * supplied reason codes. Everything in the union stays MANDATORY, because "mandatory under any
     * reason code this merchant actually receives" is the correct readiness bar when no dispute has
     * been raised yet.
     */
    public static List<RequirementSpec> baselineRequirements(List<DisputeReasonCode> reasonCodes) {
        List<DisputeReasonCode> codes = reasonCodes == null || reasonCodes.isEmpty()
                ? DEFAULT_TOP_REASON_CODES : reasonCodes;
        Set<EvidenceType> mandatory = new LinkedHashSet<>();
        Set<EvidenceType> recommended = new LinkedHashSet<>();
        for (DisputeReasonCode code : codes) {
            for (RequirementSpec spec : MATRIX.getOrDefault(code, List.of())) {
                if (spec.isMandatory()) {
                    mandatory.add(spec.type());
                } else if (spec.isRecommended()) {
                    recommended.add(spec.type());
                }
            }
        }
        recommended.removeAll(mandatory);
        List<RequirementSpec> baseline = new ArrayList<>();
        mandatory.forEach(type -> baseline.add(mandatory(type)));
        recommended.forEach(type -> baseline.add(recommended(type)));
        return List.copyOf(baseline);
    }

    /** Default expiry window for an evidence type, or {@code null} when it never expires. */
    public static Integer defaultMaxAgeDays(EvidenceType type) {
        return type == null ? null : MAX_AGE_DAYS.get(type);
    }

    /**
     * A complete default {@link PolicyView} for a merchant/reason code. Marked {@code defaultPolicy}
     * so callers and the UI can tell a seeded fallback from a published merchant policy.
     */
    public static PolicyView defaultPolicy(String merchantId, DisputeReasonCode reasonCode,
                                           List<DisputeReasonCode> topReasonCodes) {
        List<RequirementSpec> specs = reasonCode == null
                ? baselineRequirements(topReasonCodes)
                : requirements(reasonCode);
        String suffix = reasonCode == null ? "BASELINE" : reasonCode.name();
        return new PolicyView(
                "POL-DEFAULT-" + suffix,
                "POL-DEFAULT-" + suffix + "-V1",
                1,
                merchantId,
                reasonCode,
                specs,
                DEFAULT_PERMITTED_ACTIONS,
                Set.of(),
                DEFAULT_AUTO_PREPARE_MIN_CONFIDENCE,
                DEFAULT_MAX_CONTRADICTIONS,
                DEFAULT_MIN_READINESS_FOR_AUTO_PREPARE,
                DEFAULT_HUMAN_REVIEW_ABOVE_AMOUNT_MINOR,
                DEFAULT_CURRENCY,
                DEFAULT_AUTO_SUBMIT_ENABLED,
                DEFAULT_RESPONSE_WINDOW_DAYS,
                DEFAULT_EXPIRING_SOON_DAYS,
                "PLATFORM",
                null,
                Instant.EPOCH,
                null,
                true);
    }

    /** Every reason code the matrix covers - all ten of them. */
    public static Set<DisputeReasonCode> coveredReasonCodes() {
        return EnumSet.copyOf(MATRIX.keySet());
    }

    private static RequirementSpec mandatory(EvidenceType type) {
        return RequirementSpec.of(type, RequirementStrength.MANDATORY);
    }

    private static RequirementSpec recommended(EvidenceType type) {
        return RequirementSpec.of(type, RequirementStrength.RECOMMENDED);
    }

    private static RequirementSpec optional(EvidenceType type) {
        return RequirementSpec.of(type, RequirementStrength.OPTIONAL);
    }

    private static List<RequirementSpec> specs(RequirementSpec... values) {
        return List.of(values);
    }
}
