package com.laserpay.pdei.audit.repository;

import java.time.Instant;

/**
 * Filter behind {@code GET /audit/v1/events} and {@code GET /audit/v1/export}
 * (PLATFORM-CONTRACT section 8.4).
 *
 * <p>Every field is optional; a null means "do not constrain on this". The time window is
 * half-open, {@code [from, to)}, so consecutive windows tile without double-counting an entry that
 * lands exactly on a boundary.
 *
 * @param entityType one of the {@code AggregateType} names, as stored in {@code entity_type}
 * @param merchantId restricts to a single merchant chain
 * @param action     exact match on the recorded action, e.g. {@code EVIDENCE_EXPIRED}
 */
public record AuditQuery(
        String entityType,
        String entityId,
        String merchantId,
        String actor,
        String action,
        Instant from,
        Instant to,
        int page,
        int size) {

    public AuditQuery {
        page = Math.max(0, page);
        size = Math.max(1, size);
    }

    public static AuditQuery all(int page, int size) {
        return new AuditQuery(null, null, null, null, null, null, null, page, size);
    }

    public static AuditQuery forEntity(String entityType, String entityId, int page, int size) {
        return new AuditQuery(entityType, entityId, null, null, null, null, null, page, size);
    }

    public int offset() {
        return page * size;
    }

    public AuditQuery withPaging(int newPage, int newSize) {
        return new AuditQuery(entityType, entityId, merchantId, actor, action, from, to,
                newPage, newSize);
    }
}
