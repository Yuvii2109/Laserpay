package com.laserpay.pdei.audit.chain;

import com.laserpay.pdei.audit.repository.AuditEventStore;
import com.laserpay.pdei.audit.repository.AuditQuery;
import com.laserpay.pdei.common.event.AuditEvent;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * In-memory {@link AuditEventStore} that reproduces the two properties of {@code V8__audit.sql} the
 * chain logic actually depends on:
 *
 * <ul>
 *   <li>{@code sequence_no} is assigned monotonically by the store, never by the caller;</li>
 *   <li>{@code ux_audit_events_link} - one successor per link - so a second writer claiming the same
 *       {@code previous_hash} is rejected with a {@link DuplicateKeyException}, exactly as Postgres
 *       would.</li>
 * </ul>
 *
 * <p>It also exposes {@link #tamperWith} and {@link #removeAt}, which no production code path has
 * and no production code path ever should: they exist so the verifier can be tested against a
 * history that really was altered, rather than against a mock that claims it was.
 */
final class InMemoryAuditEventStore implements AuditEventStore {

    private final Map<String, Row> byId = new LinkedHashMap<>();
    private long nextSequence = 1L;

    // --- writes ---------------------------------------------------------------------------------

    @Override
    public void append(AuditEvent event) {
        if (byId.containsKey(event.auditId())) {
            return; // ON CONFLICT (audit_id) DO NOTHING
        }
        boolean linkTaken = byId.values().stream()
                .anyMatch(row -> Objects.equals(row.event.merchantId(), event.merchantId())
                        && Objects.equals(row.event.previousHash(), event.previousHash()));
        if (linkTaken) {
            throw new DuplicateKeyException(
                    "ux_audit_events_link: (" + event.merchantId() + ", " + event.previousHash() + ")");
        }
        byId.put(event.auditId(), new Row(nextSequence++, event));
    }

    // --- test-only mutations --------------------------------------------------------------------

    /** Replace a stored entry's content while leaving its recorded hash untouched: an edit. */
    void tamperWith(String auditId, String newAction) {
        Row row = byId.get(auditId);
        if (row == null) {
            throw new IllegalArgumentException("no such audit entry: " + auditId);
        }
        AuditEvent original = row.event;
        row.event = new AuditEvent(original.auditId(), original.entityType(), original.entityId(),
                original.merchantId(), newAction, original.actor(), original.actorType(),
                original.occurredAt(), original.correlationId(), original.before(), original.after(),
                original.previousHash(), original.hash());
    }

    /** Remove an entry from the middle of a chain: a deletion. */
    void removeAt(int index) {
        List<String> ids = byId.values().stream()
                .sorted(Comparator.comparingLong(row -> row.sequence))
                .map(row -> row.event.auditId())
                .toList();
        byId.remove(ids.get(index));
    }

    // --- reads ----------------------------------------------------------------------------------

    @Override
    public Optional<String> lastHash(String merchantId) {
        return chain(merchantId).stream()
                .reduce((first, second) -> second)
                .map(AuditEvent::hash);
    }

    @Override
    public List<AuditEvent> findChain(String merchantId, int limit) {
        List<AuditEvent> chain = chain(merchantId);
        return chain.subList(0, Math.min(chain.size(), limit <= 0 ? chain.size() : limit));
    }

    @Override
    public List<AuditEvent> findChainPage(String merchantId, long afterSequence, int limit) {
        return byId.values().stream()
                .filter(row -> Objects.equals(row.event.merchantId(), merchantId))
                .filter(row -> row.sequence > afterSequence)
                .sorted(Comparator.comparingLong(row -> row.sequence))
                .limit(Math.max(1, limit))
                .map(row -> row.event)
                .toList();
    }

    @Override
    public long sequenceOf(String auditId) {
        Row row = byId.get(auditId);
        return row == null ? -1L : row.sequence;
    }

    @Override
    public boolean exists(String auditId) {
        return auditId != null && byId.containsKey(auditId);
    }

    @Override
    public long countChain(String merchantId) {
        return chain(merchantId).size();
    }

    @Override
    public List<String> findChainKeys(int limit) {
        return byId.values().stream()
                .map(row -> row.event.merchantId())
                .distinct()
                .sorted()
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<AuditEvent> find(AuditQuery query) {
        List<AuditEvent> matching = byId.values().stream()
                .sorted(Comparator.comparingLong((Row row) -> row.sequence).reversed())
                .map(row -> row.event)
                .filter(event -> matches(event, query))
                .toList();
        int from = Math.min(query.offset(), matching.size());
        int to = Math.min(from + query.size(), matching.size());
        return matching.subList(from, to);
    }

    @Override
    public long count(AuditQuery query) {
        return byId.values().stream().map(row -> row.event).filter(event -> matches(event, query)).count();
    }

    @Override
    public long stream(AuditQuery query, int batchSize, long maxEvents, Consumer<AuditEvent> sink) {
        long emitted = 0L;
        for (Row row : byId.values().stream()
                .sorted(Comparator.comparingLong(r -> r.sequence)).toList()) {
            if (!matches(row.event, query)) {
                continue;
            }
            sink.accept(row.event);
            if (++emitted >= maxEvents) {
                break;
            }
        }
        return emitted;
    }

    @Override
    public Instant[] chainBounds(String merchantId) {
        List<AuditEvent> chain = chain(merchantId);
        if (chain.isEmpty()) {
            return new Instant[] {null, null};
        }
        Instant oldest = chain.stream().map(AuditEvent::occurredAt).min(Instant::compareTo).orElse(null);
        Instant newest = chain.stream().map(AuditEvent::occurredAt).max(Instant::compareTo).orElse(null);
        return new Instant[] {oldest, newest};
    }

    @Override
    public List<AuditEvent> findByEntity(String entityType, String entityId, int page, int size) {
        return find(AuditQuery.forEntity(entityType, entityId, page, size));
    }

    @Override
    public List<AuditEvent> findByFilter(String merchantId, String actor, Instant from, Instant to,
                                         int page, int size) {
        return find(new AuditQuery(null, null, merchantId, actor, null, from, to, page, size));
    }

    // --- helpers --------------------------------------------------------------------------------

    private List<AuditEvent> chain(String merchantId) {
        List<AuditEvent> chain = new ArrayList<>();
        byId.values().stream()
                .filter(row -> Objects.equals(row.event.merchantId(), merchantId))
                .sorted(Comparator.comparingLong(row -> row.sequence))
                .forEach(row -> chain.add(row.event));
        return chain;
    }

    private static boolean matches(AuditEvent event, AuditQuery query) {
        return match(query.entityType(), event.entityType())
                && match(query.entityId(), event.entityId())
                && match(query.merchantId(), event.merchantId())
                && match(query.actor(), event.actor())
                && match(query.action(), event.action())
                && (query.from() == null || !event.occurredAt().isBefore(query.from()))
                && (query.to() == null || event.occurredAt().isBefore(query.to()));
    }

    private static boolean match(String filter, String value) {
        return filter == null || filter.isBlank() || filter.equals(value);
    }

    private static final class Row {
        private final long sequence;
        private AuditEvent event;

        Row(long sequence, AuditEvent event) {
            this.sequence = sequence;
            this.event = event;
        }
    }
}
