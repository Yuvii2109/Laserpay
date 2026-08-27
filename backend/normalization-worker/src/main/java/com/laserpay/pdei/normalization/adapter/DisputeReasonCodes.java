package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.domain.DisputeReasonCode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translation table from card-network and PSP reason vocabularies to
 * {@link DisputeReasonCode} (PLATFORM-CONTRACT section 6).
 *
 * <p>Reason code is the single most consequential field on a dispute: it selects the evidence
 * requirement profile, which drives the readiness score, which decides whether a case is
 * auto-prepared or escalated. Guessing it would corrupt everything downstream, so an unmapped code
 * returns {@code null} and the caller dead-letters the event rather than substituting a plausible
 * value. Adding a network's code set is a one-line change here plus a replay from
 * {@code pdei.raw.events.v1}.
 */
public final class DisputeReasonCodes {

    private static final Map<String, DisputeReasonCode> TABLE = new HashMap<>();

    static {
        // Canonical names themselves, so an already-canonical producer passes straight through.
        for (DisputeReasonCode code : DisputeReasonCode.values()) {
            TABLE.put(key(code.name()), code);
        }

        // Visa (15.x/13.x/10.x series)
        put("13.1", DisputeReasonCode.GOODS_NOT_RECEIVED);
        put("13.2", DisputeReasonCode.SUBSCRIPTION_CANCELLED);
        put("13.3", DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED);
        put("13.5", DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED);
        put("13.6", DisputeReasonCode.CREDIT_NOT_PROCESSED);
        put("13.7", DisputeReasonCode.CREDIT_NOT_PROCESSED);
        put("12.6", DisputeReasonCode.DUPLICATE_PROCESSING);
        put("12.5", DisputeReasonCode.INCORRECT_AMOUNT);
        put("10.4", DisputeReasonCode.FRAUDULENT_TRANSACTION);
        put("10.5", DisputeReasonCode.FRAUDULENT_TRANSACTION);
        put("11.3", DisputeReasonCode.UNRECOGNIZED_TRANSACTION);

        // Mastercard (48xx series)
        put("4855", DisputeReasonCode.GOODS_NOT_RECEIVED);
        put("4853", DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED);
        put("4860", DisputeReasonCode.CREDIT_NOT_PROCESSED);
        put("4834", DisputeReasonCode.DUPLICATE_PROCESSING);
        put("4837", DisputeReasonCode.FRAUDULENT_TRANSACTION);
        put("4841", DisputeReasonCode.SUBSCRIPTION_CANCELLED);
        put("4831", DisputeReasonCode.INCORRECT_AMOUNT);

        // PSP textual vocabularies
        put("product_not_received", DisputeReasonCode.GOODS_NOT_RECEIVED);
        put("merchandise_not_received", DisputeReasonCode.GOODS_NOT_RECEIVED);
        put("goods_not_received", DisputeReasonCode.GOODS_NOT_RECEIVED);
        put("service_not_received", DisputeReasonCode.SERVICE_NOT_RENDERED);
        put("services_not_rendered", DisputeReasonCode.SERVICE_NOT_RENDERED);
        put("product_unacceptable", DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED);
        put("not_as_described", DisputeReasonCode.PRODUCT_NOT_AS_DESCRIBED);
        put("duplicate", DisputeReasonCode.DUPLICATE_PROCESSING);
        put("credit_not_processed", DisputeReasonCode.CREDIT_NOT_PROCESSED);
        put("subscription_canceled", DisputeReasonCode.SUBSCRIPTION_CANCELLED);
        put("subscription_cancelled", DisputeReasonCode.SUBSCRIPTION_CANCELLED);
        put("fraudulent", DisputeReasonCode.FRAUDULENT_TRANSACTION);
        put("unrecognized", DisputeReasonCode.UNRECOGNIZED_TRANSACTION);
        put("incorrect_amount", DisputeReasonCode.INCORRECT_AMOUNT);
        put("paid_by_other_means", DisputeReasonCode.PAID_BY_OTHER_MEANS);
        put("check_returned", DisputeReasonCode.PAID_BY_OTHER_MEANS);
    }

    private DisputeReasonCodes() {
    }

    /**
     * Canonical reason code name for a source value.
     *
     * @return the enum constant name, or {@code null} when the code is not in the table - callers
     *         must treat {@code null} as unmappable and dead-letter the event
     */
    public static String canonical(String sourceValue) {
        DisputeReasonCode code = resolve(sourceValue);
        return code == null ? null : code.name();
    }

    /** Typed variant of {@link #canonical}; {@code null} when unmapped. */
    public static DisputeReasonCode resolve(String sourceValue) {
        if (sourceValue == null || sourceValue.isBlank()) {
            return null;
        }
        return TABLE.get(key(sourceValue));
    }

    private static void put(String sourceValue, DisputeReasonCode code) {
        TABLE.put(key(sourceValue), code);
    }

    /** Case-insensitive, separator-insensitive key: {@code 13.1}, {@code 13-1} and {@code 131} agree. */
    private static String key(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == ' ') {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
