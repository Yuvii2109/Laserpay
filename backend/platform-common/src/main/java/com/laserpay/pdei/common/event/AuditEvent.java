package com.laserpay.pdei.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;

import java.time.Instant;

/**
 * One tamper-evident audit record, published to {@code pdei.audit.events.v1} and persisted by
 * audit-service into the hash-chained {@code pdei.audit_events} table
 * (PLATFORM-CONTRACT sections 4 and 5).
 *
 * <p><strong>Chaining.</strong> Each record stores the {@code hash} of its predecessor in
 * {@code previousHash} and its own {@code hash} over the canonical JSON of every other field.
 * Because {@code previousHash} is one of those fields, editing any historical record changes its
 * hash and breaks every link after it - which is exactly what
 * {@code GET /audit/verify-chain} detects and reports as the first divergence.
 *
 * <p>The chain is per merchant: {@link Hashes#GENESIS_HASH} seeds a merchant's first record.
 *
 * <p>Usage:
 * <pre>{@code
 * AuditEvent record = new AuditEvent(Ids.audit(), "EVIDENCE", evidenceId, merchantId,
 *         "EVIDENCE_INVALIDATED", "state-builder-worker", ActorType.SYSTEM, clock.now(),
 *         correlationId, beforeNode, afterNode, previousHash, null).withHash();
 * }</pre>
 */
public record AuditEvent(String auditId,
                         String entityType,
                         String entityId,
                         String merchantId,
                         String action,
                         String actor,
                         ActorType actorType,
                         Instant occurredAt,
                         String correlationId,
                         JsonNode before,
                         JsonNode after,
                         String previousHash,
                         String hash) {

    /** Field excluded from the hash preimage - it is the output, not an input. */
    private static final String HASH_FIELD = "hash";

    public AuditEvent {
        if (auditId == null || auditId.isBlank()) {
            throw new ValidationException("auditId is required on AuditEvent");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new ValidationException("entityType is required on AuditEvent");
        }
        if (action == null || action.isBlank()) {
            throw new ValidationException("action is required on AuditEvent");
        }
        if (occurredAt == null) {
            throw new ValidationException("occurredAt is required on AuditEvent");
        }
        actorType = actorType == null ? ActorType.SYSTEM : actorType;
        actor = (actor == null || actor.isBlank()) ? "system" : actor;
        previousHash = (previousHash == null || previousHash.isBlank())
                ? Hashes.GENESIS_HASH
                : previousHash;
    }

    /**
     * SHA-256 over the canonical JSON of every field except {@code hash}
     * (docs/SHARED-LIBRARY-API.md section 1.3).
     *
     * <p>Deterministic: canonical JSON sorts keys at every depth and instants serialise as ISO-8601
     * UTC, so the same logical record hashes identically on any JVM, in any service, at any time.
     */
    public String computeHash() {
        ObjectNode node = (ObjectNode) Json.tree(this);
        node.remove(HASH_FIELD);
        return Hashes.sha256Hex(Json.canonical(node));
    }

    /** This record with {@link #computeHash()} stored in {@code hash}. */
    public AuditEvent withHash() {
        return new AuditEvent(auditId, entityType, entityId, merchantId, action, actor, actorType,
                occurredAt, correlationId, before, after, previousHash, computeHash());
    }

    /** Links this record after {@code previous} and seals it. */
    public AuditEvent chainedAfter(AuditEvent previous) {
        String prev = previous == null ? Hashes.GENESIS_HASH : previous.hash();
        return new AuditEvent(auditId, entityType, entityId, merchantId, action, actor, actorType,
                occurredAt, correlationId, before, after, prev, null).withHash();
    }

    /** True when the stored hash still matches the record content. */
    public boolean verifyHash() {
        return hash != null && hash.equals(computeHash());
    }

    /** True when this record correctly follows {@code previous} and its own hash is intact. */
    public boolean verifyLink(AuditEvent previous) {
        String expectedPrevious = previous == null ? Hashes.GENESIS_HASH : previous.hash();
        return previousHash.equals(expectedPrevious) && verifyHash();
    }
}
