package com.laserpay.pdei.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Base class for MUTABLE entities: adds JPA optimistic locking on the {@code version} column.
 *
 * <p>Every mutable aggregate in PDEI is updated by concurrent Kafka consumers replaying late,
 * duplicate or out-of-order events. Optimistic locking turns a lost update into an
 * {@code OptimisticLockingFailureException}, which the consumer retries (and, being
 * idempotent, converges).
 *
 * <p>Append-only tables (processed_events, evidence_versions, policy_versions, audit_events,
 * ai_admission_log) deliberately do NOT extend this class: they are never updated.
 */
@MappedSuperclass
public abstract class VersionedEntity extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
