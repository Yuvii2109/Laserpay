package com.laserpay.pdei.common.kafka;

import com.laserpay.pdei.common.event.EventType;

import java.util.List;
import java.util.Map;

/**
 * Kafka topic names and partition counts (PLATFORM-CONTRACT section 4).
 *
 * <p>Topics are versioned in the name ({@code .v1}): a breaking envelope change ships as
 * {@code .v2} alongside, never as a mutation of the existing topic.
 *
 * <p>Partition counts are part of the contract because ordering guarantees depend on them: the
 * partition key is always {@code merchantId + ":" + aggregateId}, so every event for one aggregate
 * lands on one partition and is processed in order by a single consumer instance.
 */
public final class Topics {

    public static final String RAW_EVENTS = "pdei.raw.events.v1";
    public static final String CANONICAL_EVENTS = "pdei.canonical.events.v1";
    public static final String EVIDENCE_EVENTS = "pdei.evidence.events.v1";
    public static final String READINESS_EVENTS = "pdei.readiness.events.v1";
    public static final String DISPUTE_EVENTS = "pdei.dispute.events.v1";
    public static final String CASE_EVENTS = "pdei.case.events.v1";
    public static final String AUDIT_EVENTS = "pdei.audit.events.v1";
    public static final String DLQ = "pdei.dlq.v1";

    /** Every topic, in contract order. */
    public static final List<String> ALL = List.of(
            RAW_EVENTS, CANONICAL_EVENTS, EVIDENCE_EVENTS, READINESS_EVENTS,
            DISPUTE_EVENTS, CASE_EVENTS, AUDIT_EVENTS, DLQ);

    private static final Map<String, Integer> PARTITIONS = Map.of(
            RAW_EVENTS, 12,
            CANONICAL_EVENTS, 12,
            EVIDENCE_EVENTS, 12,
            READINESS_EVENTS, 12,
            DISPUTE_EVENTS, 12,
            CASE_EVENTS, 12,
            AUDIT_EVENTS, 6,
            DLQ, 6);

    /** Single-broker dev cluster: replication factor is necessarily 1. */
    public static final short DEV_REPLICATION_FACTOR = 1;

    private Topics() {
    }

    /**
     * Declared partition count for a topic.
     *
     * @throws IllegalArgumentException for a topic outside the contract
     */
    public static int partitions(String topic) {
        Integer count = PARTITIONS.get(topic);
        if (count == null) {
            throw new IllegalArgumentException("Unknown PDEI topic: " + topic);
        }
        return count;
    }

    /**
     * The topic a canonical event of this type belongs on.
     *
     * <p>Evidence, readiness, dispute and case events get their own topics so that consumers
     * interested only in those can subscribe narrowly instead of filtering the full canonical
     * stream; everything else flows on {@link #CANONICAL_EVENTS}.
     */
    public static String forEventType(EventType eventType) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (eventType.isEvidenceEvent()) {
            return EVIDENCE_EVENTS;
        }
        if (eventType.isReadinessEvent()) {
            return READINESS_EVENTS;
        }
        if (eventType.isDisputeEvent()) {
            return DISPUTE_EVENTS;
        }
        if (eventType.isCaseEvent()) {
            return CASE_EVENTS;
        }
        if (eventType.isAuditEvent()) {
            return AUDIT_EVENTS;
        }
        return CANONICAL_EVENTS;
    }
}
