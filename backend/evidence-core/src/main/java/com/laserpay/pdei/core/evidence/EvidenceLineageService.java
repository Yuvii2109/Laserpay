package com.laserpay.pdei.core.evidence;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.EvidenceEdge;
import com.laserpay.pdei.core.model.EvidenceGraph;
import com.laserpay.pdei.core.model.EvidenceNode;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.spi.EvidenceRelationship;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.EvidenceVersionRecord;
import com.laserpay.pdei.core.util.CoreErrors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Version chains and provenance walks, behind {@code GET /api/v1/evidence/{evidenceId}/versions} and
 * {@code /lineage}.
 *
 * <p>Two different questions are answered here:</p>
 * <ul>
 *   <li><b>Version chain</b> - the ordered history of one logical document, v1 to vN, following
 *       {@code parentEvidenceId} up to the root and back down through its children.</li>
 *   <li><b>Provenance</b> - everything the artifact is connected to: the stored object versions, the
 *       source events it came from and every declared relationship.</li>
 * </ul>
 *
 * <p>Both walks are cycle-safe. A cycle should be impossible - versions only ever point backwards -
 * but a corrupted relationship row must not hang an API thread.</p>
 */
public class EvidenceLineageService {

    private static final int MAX_DEPTH = 256;
    private static final String ENTITY_TYPE = "EVIDENCE";

    private final EvidenceRepositoryPort repository;
    private final Clocks clock;

    public EvidenceLineageService(EvidenceRepositoryPort repository, Clocks clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The full version chain containing this artifact, oldest version first. */
    public List<EvidenceView> versionChain(String evidenceId) {
        EvidenceView start = repository.findById(evidenceId)
                .orElseThrow(() -> CoreErrors.notFound(ENTITY_TYPE, evidenceId));
        EvidenceView root = rootOf(start);

        Map<String, EvidenceView> chain = new LinkedHashMap<>();
        Deque<EvidenceView> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < MAX_DEPTH) {
            EvidenceView current = queue.poll();
            if (chain.putIfAbsent(current.evidenceId(), current) != null) {
                continue;
            }
            queue.addAll(repository.findChildren(current.evidenceId()));
        }
        return chain.values().stream()
                .sorted(Comparator.comparingInt(EvidenceView::version)
                        .thenComparing(EvidenceView::evidenceId))
                .toList();
    }

    /** The oldest ancestor of an artifact - v1 of its chain. */
    public EvidenceView rootOf(EvidenceView view) {
        EvidenceView current = view;
        int guard = 0;
        Set<String> seen = new LinkedHashSet<>();
        while (current.parentEvidenceId() != null && guard++ < MAX_DEPTH && seen.add(current.evidenceId())) {
            Optional<EvidenceView> parent = repository.findById(current.parentEvidenceId());
            if (parent.isEmpty()) {
                return current;
            }
            current = parent.get();
        }
        return current;
    }

    /** The current (non-superseded) head of the chain this artifact belongs to, if any. */
    public Optional<EvidenceView> currentHead(String evidenceId) {
        return versionChain(evidenceId).stream()
                .filter(EvidenceView::isUsable)
                .max(Comparator.comparingInt(EvidenceView::version));
    }

    /** Stored object versions of one artifact, oldest first. */
    public List<EvidenceVersionRecord> storedVersions(String evidenceId) {
        return repository.findVersions(evidenceId).stream()
                .sorted(Comparator.comparingInt(EvidenceVersionRecord::version))
                .toList();
    }

    /**
     * Provenance graph: the version chain plus every declared relationship, rendered with the same
     * node/edge model as the transaction graph so the frontend can reuse one renderer.
     */
    public EvidenceGraph lineage(String evidenceId) {
        List<EvidenceView> chain = versionChain(evidenceId);
        Map<String, EvidenceNode> nodes = new LinkedHashMap<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();

        for (EvidenceView view : chain) {
            nodes.putIfAbsent(view.evidenceId(), new EvidenceNode(view.evidenceId(), AggregateType.EVIDENCE,
                    view.type() + " v" + view.version(), String.valueOf(view.status()), view.createdAt(),
                    Map.of("sha256", String.valueOf(view.sha256()),
                            "objectKey", String.valueOf(view.objectKey()),
                            "sourceEventId", String.valueOf(view.sourceEventId()))));
            if (view.parentEvidenceId() != null) {
                addEdge(edges, edgeKeys, view.evidenceId(), view.parentEvidenceId(), EvidenceEdge.SUPERSEDES);
            }
            if (view.sourceEventId() != null && !view.sourceEventId().isBlank()) {
                String eventNodeId = "EVENT:" + view.sourceEventId();
                nodes.putIfAbsent(eventNodeId, new EvidenceNode(eventNodeId, AggregateType.EVIDENCE,
                        "source event", null, view.observedAt(),
                        Map.of("eventId", view.sourceEventId())));
                addEdge(edges, edgeKeys, view.evidenceId(), eventNodeId, EvidenceEdge.DERIVED_FROM);
            }
            for (EvidenceRelationship relationship : repository.findRelationships(view.evidenceId())) {
                nodes.computeIfAbsent(relationship.toEvidenceId(), id -> repository.findById(id)
                        .map(other -> new EvidenceNode(other.evidenceId(), AggregateType.EVIDENCE,
                                other.type() + " v" + other.version(), String.valueOf(other.status()),
                                other.createdAt(), Map.of("sha256", String.valueOf(other.sha256()))))
                        .orElseGet(() -> EvidenceNode.of(id, AggregateType.EVIDENCE, id, null, null)));
                addEdge(edges, edgeKeys, relationship.fromEvidenceId(), relationship.toEvidenceId(),
                        relationship.relation());
            }
        }
        return new EvidenceGraph(evidenceId, List.copyOf(nodes.values()), edges, clock.now());
    }

    private static void addEdge(List<EvidenceEdge> edges, Set<String> keys, String from, String to,
                                String relation) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        if (keys.add(from + ">" + to + ">" + relation)) {
            edges.add(EvidenceEdge.of(from, to, relation));
        }
    }
}
