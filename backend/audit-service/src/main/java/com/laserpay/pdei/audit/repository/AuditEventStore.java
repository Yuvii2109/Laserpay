package com.laserpay.pdei.audit.repository;

import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.core.spi.AuditRepositoryPort;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Append-only access to {@code pdei.audit_events}.
 *
 * <p>Extends {@link AuditRepositoryPort} so this service's implementation also satisfies
 * {@code evidence-core}'s port (and therefore takes over from the shared JDBC adapter), and adds
 * what only the audit service itself needs: existence checks for dedupe, chain traversal by
 * {@code sequence_no}, and a streaming cursor for the NDJSON export.
 *
 * <p><strong>There is no update method and no delete method, at any level of this interface.</strong>
 * That is not an omission. A hash chain that can be edited is a log with extra steps.
 */
public interface AuditEventStore extends AuditRepositoryPort {

    /** Merchant chain used for entries that belong to the platform rather than to a merchant. */
    String PLATFORM_CHAIN = "PLATFORM";

    /** True when this audit id has already been persisted - the dedupe primitive for redelivery. */
    boolean exists(String auditId);

    /**
     * One page of a merchant chain in insertion order.
     *
     * @param afterSequence exclusive lower bound on {@code sequence_no}; 0 starts at the beginning
     */
    List<AuditEvent> findChainPage(String merchantId, long afterSequence, int limit);

    /** The {@code sequence_no} of an entry, for reporting an index the database agrees with. */
    long sequenceOf(String auditId);

    /** Entries in this merchant chain. */
    long countChain(String merchantId);

    /** Every merchant that has at least one entry - the input to a full-platform verification. */
    List<String> findChainKeys(int limit);

    /** Filtered read behind {@code GET /audit/v1/events}. */
    List<AuditEvent> find(AuditQuery query);

    /** Total matching {@code query}, ignoring its paging fields. */
    long count(AuditQuery query);

    /**
     * Stream every entry matching {@code query} to {@code sink}, in stable order, fetching
     * {@code batchSize} rows at a time.
     *
     * <p>Batched keyset traversal rather than one huge result set: the export endpoint must be able
     * to emit a million entries without either the server or the client holding them all.
     *
     * @return number of entries emitted
     */
    long stream(AuditQuery query, int batchSize, long maxEvents, Consumer<AuditEvent> sink);

    /** Oldest and newest {@code occurred_at} in a merchant chain, or nulls when it is empty. */
    Instant[] chainBounds(String merchantId);
}
