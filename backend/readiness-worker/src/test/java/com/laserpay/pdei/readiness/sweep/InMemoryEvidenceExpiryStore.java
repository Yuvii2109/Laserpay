package com.laserpay.pdei.readiness.sweep;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.readiness.persistence.EvidenceExpiryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link EvidenceExpiryStore} that reproduces the two behaviours the sweep depends on:
 * the selection predicates of the two queries, and the conditional (compare-and-set) semantics of
 * the transition statement.
 *
 * <p>Written by hand rather than mocked so the test asserts against a store that behaves like the
 * database - in particular, {@link #transition} refuses when the current status is outside
 * {@code from}, which is what makes the real sweep idempotent.
 */
final class InMemoryEvidenceExpiryStore implements EvidenceExpiryStore {

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final List<String> transitions = new ArrayList<>();

    /** Reject the next transition of this artifact once, simulating a concurrent worker winning. */
    private String contendedEvidenceId;

    void add(String evidenceId, String transactionId, EvidenceStatus status, Instant expiresAt) {
        rows.put(evidenceId, new Row(evidenceId, "MER-00000001", transactionId,
                EvidenceType.DELIVERY_PROOF, status, "evt-source-" + evidenceId, expiresAt));
    }

    void contend(String evidenceId) {
        this.contendedEvidenceId = evidenceId;
    }

    List<String> transitions() {
        return List.copyOf(transitions);
    }

    @Override
    public List<ExpiringEvidence> findDueForExpiry(Instant now, int limit) {
        return rows.values().stream()
                .filter(row -> row.expiresAt != null && !row.expiresAt.isAfter(now))
                .filter(row -> EXPIRABLE.contains(row.status))
                .limit(Math.max(1, limit))
                .map(Row::toView)
                .toList();
    }

    @Override
    public List<ExpiringEvidence> findEnteringWarningWindow(Instant now, Instant windowEnd, int limit) {
        return rows.values().stream()
                .filter(row -> row.expiresAt != null)
                .filter(row -> row.expiresAt.isAfter(now) && !row.expiresAt.isAfter(windowEnd))
                .filter(row -> row.status == EvidenceStatus.ACTIVE)
                .limit(Math.max(1, limit))
                .map(Row::toView)
                .toList();
    }

    @Override
    public boolean transition(String evidenceId, Collection<EvidenceStatus> from, EvidenceStatus to,
                              Instant at) {
        Row row = rows.get(evidenceId);
        if (row == null || !from.contains(row.status)) {
            return false;
        }
        if (evidenceId.equals(contendedEvidenceId)) {
            // Another worker got there first: the row moves, but not by our hand.
            contendedEvidenceId = null;
            row.status = to;
            return false;
        }
        row.status = to;
        transitions.add(evidenceId + ":" + to.name());
        return true;
    }

    @Override
    public EvidenceStatus statusOf(String evidenceId) {
        Row row = rows.get(evidenceId);
        return row == null ? null : row.status;
    }

    private static final class Row {
        private final String evidenceId;
        private final String merchantId;
        private final String transactionId;
        private final EvidenceType type;
        private final String sourceEventId;
        private final Instant expiresAt;
        private EvidenceStatus status;

        Row(String evidenceId, String merchantId, String transactionId, EvidenceType type,
            EvidenceStatus status, String sourceEventId, Instant expiresAt) {
            this.evidenceId = evidenceId;
            this.merchantId = merchantId;
            this.transactionId = transactionId;
            this.type = type;
            this.status = status;
            this.sourceEventId = sourceEventId;
            this.expiresAt = expiresAt;
        }

        ExpiringEvidence toView() {
            return new ExpiringEvidence(evidenceId, merchantId, transactionId, type, status,
                    sourceEventId, expiresAt);
        }
    }
}
