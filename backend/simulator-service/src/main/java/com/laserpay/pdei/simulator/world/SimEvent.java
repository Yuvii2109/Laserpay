package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;

import java.time.Instant;

/**
 * One generated event, ready to publish to {@code pdei.raw.events.v1}.
 *
 * <p>The {@link RawEventEnvelope} is the wire form and the only thing that leaves this service.
 * The other fields are the generator's own bookkeeping: they let the emitter partition and log
 * sensibly, let chaos target "the delivery event of transaction TX-9", and let the determinism
 * test assert on a stable ordering without re-parsing JSON.
 *
 * @param sequence      position in the generated stream, stable for a given (seed, spec)
 * @param occurredAt    when the fact happened in the simulated source system
 * @param observedAt    when PDEI would see it; later than {@code occurredAt} for a late event
 * @param canonicalType the {@link EventType} normalization-worker is expected to produce
 * @param aggregateType aggregate this event is about
 * @param aggregateId   id of that aggregate
 * @param merchantId    owning merchant
 * @param transactionId owning transaction, null for merchant-level events
 * @param envelope      the raw envelope published to Kafka
 * @param artifact      synthetic bytes for an EvidenceAdded event, null otherwise
 */
public record SimEvent(int sequence,
                       Instant occurredAt,
                       Instant observedAt,
                       EventType canonicalType,
                       AggregateType aggregateType,
                       String aggregateId,
                       String merchantId,
                       String transactionId,
                       RawEventEnvelope envelope,
                       SyntheticArtifact artifact) {

    /**
     * Kafka partition key for {@code pdei.raw.events.v1}: {@code merchantId + ":" + aggregateId},
     * mandated by PLATFORM-CONTRACT section 4 and shared with the other producer on that topic
     * (ingestion-service). Keying by aggregate is what keeps every event about one aggregate on one
     * partition, so normalization-worker - which consumes this topic with concurrency > 1 - cannot
     * normalise two events of the same aggregate out of order.
     */
    public String partitionKey() {
        return merchantId + ":" + aggregateId;
    }

    public boolean isEvidence() {
        return canonicalType == EventType.EvidenceAdded;
    }

    public boolean isDispute() {
        return canonicalType.isDisputeEvent();
    }

    /** True when this event was generated as a late arrival. */
    public boolean isLate() {
        return observedAt.isAfter(occurredAt.plusSeconds(60));
    }

    /** Copy with a new sequence number, used after the generator reorders the stream. */
    public SimEvent withSequence(int newSequence) {
        return new SimEvent(newSequence, occurredAt, observedAt, canonicalType, aggregateType,
                aggregateId, merchantId, transactionId, envelope, artifact);
    }
}
