package com.laserpay.pdei.core.model;

import java.time.Instant;
import java.util.List;

/** Node/edge projection of everything attached to a transaction (or to one evidence lineage). */
public record EvidenceGraph(
        String rootId,
        List<EvidenceNode> nodes,
        List<EvidenceEdge> edges,
        Instant generatedAt) {

    public EvidenceGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static EvidenceGraph empty(String rootId, Instant at) {
        return new EvidenceGraph(rootId, List.of(), List.of(), at);
    }
}
