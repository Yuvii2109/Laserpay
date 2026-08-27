package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.event.AuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only port for {@code pdei.audit_events}.
 *
 * <p>There is deliberately no update or delete method: the audit log is hash chained and any
 * mutation would break {@code GET /audit/verify-chain}.</p>
 */
public interface AuditRepositoryPort {

    void append(AuditEvent event);

    /** Hash of the newest event in this merchant chain, {@code empty} for a fresh chain. */
    Optional<String> lastHash(String merchantId);

    /** Whole chain in insertion order - used by chain verification. */
    List<AuditEvent> findChain(String merchantId, int limit);

    List<AuditEvent> findByEntity(String entityType, String entityId, int page, int size);

    List<AuditEvent> findByFilter(String merchantId, String actor, Instant from, Instant to, int page, int size);
}
