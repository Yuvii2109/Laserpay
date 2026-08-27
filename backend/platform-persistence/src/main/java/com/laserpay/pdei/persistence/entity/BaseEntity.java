package com.laserpay.pdei.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.Objects;

/**
 * Timestamp bookkeeping shared by every PDEI entity.
 *
 * <p>Time rule: {@link Instant} only, persisted to {@code TIMESTAMPTZ}, always UTC.
 * {@code LocalDateTime} never appears in this module.
 *
 * <p>The identifier stays in the concrete entity because each table uses its own
 * human-readable prefixed primary key column ({@code merchant_id}, {@code evidence_id}, ...);
 * subclasses expose it through {@link #getId()}.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The prefixed identifier of this row (e.g. {@code EV-4F2A9C11}). */
    public abstract String getId();

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onPreUpdate() {
        updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Identity is the assigned business id. Rows without an id yet are only equal to
     * themselves, which keeps unsaved entities usable in collections.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || !getClass().equals(o.getClass())) {
            return false;
        }
        String id = getId();
        return id != null && id.equals(((BaseEntity) o).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getId() + "]";
    }
}
