package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Durable consumer-side dedupe marker: "(eventId) has been handled by (consumerGroup)".
 *
 * <p>Rule 9/10 of the contract: every consumer tolerates duplicates and out-of-order delivery.
 * Redis {@code pdei:idem:{eventId}} is the fast path; this table is the authoritative one and
 * survives a Redis flush. Writers must go through
 * {@code ProcessedEventRepository.markProcessed(eventId, consumerGroup)} which performs an
 * {@code INSERT ... ON CONFLICT DO NOTHING} and reports whether this was the first sighting.
 *
 * <p>Append-only: rows are never updated, only inserted and (eventually) pruned by age.
 */
@Entity
@Table(name = "processed_events", schema = PdeiSchema.NAME)
public class ProcessedEventEntity {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEventEntity() {
    }

    public ProcessedEventEntity(String eventId, String consumerGroup, Instant processedAt) {
        this.id = new ProcessedEventId(eventId, consumerGroup);
        this.processedAt = processedAt;
    }

    @PrePersist
    void onPrePersist() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }

    public ProcessedEventId getId() {
        return id;
    }

    public void setId(ProcessedEventId id) {
        this.id = id;
    }

    public String getEventId() {
        return id == null ? null : id.getEventId();
    }

    public String getConsumerGroup() {
        return id == null ? null : id.getConsumerGroup();
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    @Override
    public String toString() {
        return "ProcessedEventEntity[" + id + "]";
    }
}
