package com.laserpay.pdei.core.model;

import java.util.Map;

/** A directed relation between two {@link EvidenceNode}s. */
public record EvidenceEdge(
        String from,
        String to,
        String relation,
        Map<String, Object> attributes) {

    public static final String HAS_PAYMENT = "HAS_PAYMENT";
    public static final String HAS_ORDER = "HAS_ORDER";
    public static final String HAS_REFUND = "HAS_REFUND";
    public static final String HAS_COMMUNICATION = "HAS_COMMUNICATION";
    public static final String SHIPPED_AS = "SHIPPED_AS";
    public static final String DELIVERED_AS = "DELIVERED_AS";
    public static final String REFUNDS = "REFUNDS";
    public static final String EVIDENCES = "EVIDENCES";
    public static final String SUPERSEDES = "SUPERSEDES";
    public static final String DERIVED_FROM = "DERIVED_FROM";
    public static final String CONTRADICTS = "CONTRADICTS";
    public static final String RELATES_TO = "RELATES_TO";

    public EvidenceEdge {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static EvidenceEdge of(String from, String to, String relation) {
        return new EvidenceEdge(from, to, relation, Map.of());
    }
}
