package com.laserpay.pdei.common.id;

import java.util.List;

/**
 * Human-readable identifier prefixes (PLATFORM-CONTRACT section 5).
 *
 * <p>Every primary key in the {@code pdei} schema is a {@code VARCHAR(64)} carrying one of these
 * prefixes, which makes logs, Kafka keys and audit trails readable without a join.
 */
public final class IdPrefix {

    public static final String MERCHANT = "MER-";
    public static final String CUSTOMER = "CUS-";
    public static final String TRANSACTION = "TX-";
    public static final String PAYMENT = "PAY-";
    public static final String ORDER = "ORD-";
    public static final String SHIPMENT = "SHP-";
    public static final String DELIVERY = "DLV-";
    public static final String REFUND = "REF-";
    public static final String COMMUNICATION = "COM-";
    public static final String EVIDENCE = "EV-";
    public static final String POLICY = "POL-";
    public static final String DISPUTE = "DSP-";
    public static final String CASE = "CASE-";
    public static final String INVESTIGATION = "INV-";
    public static final String AUDIT = "AUD-";
    public static final String SIMULATION = "SIM-";

    /** All prefixes, declaration order. */
    public static final List<String> ALL = List.of(
            MERCHANT, CUSTOMER, TRANSACTION, PAYMENT, ORDER, SHIPMENT, DELIVERY, REFUND,
            COMMUNICATION, EVIDENCE, POLICY, DISPUTE, CASE, INVESTIGATION, AUDIT, SIMULATION);

    private IdPrefix() {
    }

    /**
     * Returns the prefix an id carries, or {@code null} when it carries none.
     * The longest match wins so that {@code CASE-} is never mistaken for a shorter prefix.
     */
    public static String prefixOf(String id) {
        if (id == null) {
            return null;
        }
        String best = null;
        for (String prefix : ALL) {
            if (id.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best;
    }
}
