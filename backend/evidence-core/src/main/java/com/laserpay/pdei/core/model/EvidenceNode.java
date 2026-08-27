package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.event.AggregateType;

import java.time.Instant;
import java.util.Map;

/** A vertex of {@link EvidenceGraph}: a financial entity or an evidence artifact. */
public record EvidenceNode(
        String id,
        AggregateType type,
        String label,
        String status,
        Instant at,
        Map<String, Object> attributes) {

    public EvidenceNode {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static EvidenceNode of(String id, AggregateType type, String label, String status, Instant at) {
        return new EvidenceNode(id, type, label, status, at, Map.of());
    }
}
