package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.statebuilder.forward.EventForwarder;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Bridges EVIDENCE events that arrive on the canonical topic onto {@code pdei.evidence.events.v1}.
 *
 * <h2>Why this handler exists at all</h2>
 *
 * Most evidence events are born on the evidence topic: {@code EvidenceService} publishes
 * {@code EvidenceAdded} there directly, and {@code readiness-worker} consumes it. But evidence facts
 * can also enter from <em>outside</em> - a merchant portal entry, a partner system that already
 * holds documents, a future integration that reports artifacts rather than lifecycle events. Those
 * arrive through ingestion and normalization like everything else, which puts them on the canonical
 * topic where readiness-worker is not listening.
 *
 * <p>This handler closes that gap: it forwards them, unchanged, to the topic the evidence consumers
 * actually read. Same {@code eventId}, so a consumer that somehow sees both copies still processes
 * one.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It does not write the {@code evidence}, {@code evidence_versions} or
 * {@code evidence_relationships} tables. Those belong to {@code evidence-core}, which hashes content
 * before recording it, maintains the version chain, and appends to the hash-chained audit log.
 * A second writer would be a second source of truth for provenance, and provenance with two sources
 * of truth is provenance with none.
 *
 * <p>It also does not create evidence from these events: an externally-asserted
 * {@code EvidenceAdded} carries a reference, not bytes, and evidence without verifiable content is
 * exactly what the integrity model exists to prevent. Registering such an artifact is the merchant
 * portal upload path ({@code POST /api/v1/evidence}), which supplies the bytes.
 */
public class EvidenceEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(EvidenceEventHandler.class);

    private final EventForwarder forwarder;

    public EvidenceEventHandler(EventForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.EvidenceAdded, EventType.EvidenceExpired,
                EventType.EvidenceInvalidated);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String evidenceId = event.aggregateId();
        String transactionId = TransactionProjection.resolveTransactionId(
                CanonicalPayloads.text(payload, "transactionId"), null);

        log.info("forwarding {} for evidence {} (transaction {}) from {} to {}", event.eventType(),
                evidenceId, transactionId == null ? "unknown" : transactionId, event.source(),
                Topics.EVIDENCE_EVENTS);

        forwarder.forward(Topics.EVIDENCE_EVENTS, event);
    }
}
