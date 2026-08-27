package com.laserpay.pdei.audit.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;

import java.util.Objects;

/**
 * Turns a canonical domain event into an audit entry.
 *
 * <p>The audit trail must answer "what happened to this entity, and who did it" for every entity in
 * the platform - not only for the handful of actions a service happens to remember to report
 * explicitly. Deriving an entry from every domain event on every topic is what closes that gap
 * (reference §12, non-negotiable rule 8).
 *
 * <p>The mapping is mechanical and total:
 *
 * <pre>
 *   auditId      deterministic from eventId  -> redelivery is a no-op, replay is idempotent
 *   entityType   aggregateType.name()        -> exactly the ck_audit_events_entity_type vocabulary
 *   entityId     aggregateId
 *   merchantId   merchantId                  -> selects the chain
 *   action       eventType.name()            -> e.g. ShipmentDelivered, ReadinessRecomputed
 *   actor        source.name()               -> which system asserted the fact
 *   actorType    derived from source
 *   occurredAt   occurredAt                  -> when the fact happened, not when we saw it
 *   before       null                        -> a canonical event carries no prior state
 *   after        payload + envelope metadata
 * </pre>
 *
 * <p><strong>The audit id must be deterministic.</strong> The same canonical event redelivered after
 * a consumer rebalance, or replayed from offset zero six months later, must map to the same audit
 * id, or the chain grows a second link for a fact it already recorded. It is derived by hashing the
 * event id, so it is stable, collision-resistant, and satisfies {@code ck_audit_events_id_prefix}.
 */
public final class CanonicalAuditMapper {

    /** {@code Ids.audit()} produces {@code AUD-XXXXXXXX}; this keeps the same shape and width. */
    private static final int ID_HEX_LENGTH = 16;

    private CanonicalAuditMapper() {
    }

    /**
     * Derive the audit entry for a domain event.
     *
     * <p>{@code previousHash} is left at {@link Hashes#GENESIS_HASH} and {@code hash} is computed
     * over that: {@code AuditChainAppender} re-seals the entry against the real chain head when it
     * appends. The entry is therefore self-consistent at every point, never half-formed.
     */
    public static AuditEvent toAuditEvent(CanonicalEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        AggregateType aggregateType = event.aggregateType() == null
                ? event.eventType().aggregateType()
                : event.aggregateType();

        return new AuditEvent(
                auditIdFor(event.eventId()),
                aggregateType.name(),
                event.aggregateId(),
                event.merchantId(),
                event.eventType().name(),
                actorFor(event.source()),
                actorTypeFor(event.source()),
                event.occurredAt(),
                event.correlationId(),
                null,
                afterState(event),
                Hashes.GENESIS_HASH,
                null).withHash();
    }

    /**
     * Stable audit id for an event id.
     *
     * <p>A hash rather than a substring of the event id: event ids are UUIDs today, but the contract
     * only requires them to be strings, and slicing an arbitrary string would neither be
     * fixed-width nor collision-resistant.
     */
    public static String auditIdFor(String eventId) {
        String digest = Hashes.sha256Hex("audit:" + String.valueOf(eventId));
        return "AUD-" + digest.substring(0, ID_HEX_LENGTH).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The "after" state of a derived entry: the event payload, plus the envelope fields an auditor
     * needs to trace the record back to the exact message that produced it.
     */
    private static JsonNode afterState(CanonicalEvent event) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("eventId", event.eventId());
        node.put("eventType", event.eventType().name());
        node.put("schemaVersion", event.schemaVersion());
        node.put("source", event.source() == null ? null : event.source().name());
        node.put("idempotencyKey", event.idempotencyKey());
        node.put("observedAt", event.observedAt() == null ? null : event.observedAt().toString());
        if (event.causationId() != null) {
            node.put("causationId", event.causationId());
        }
        if (event.payload() != null && !event.payload().isNull()) {
            node.set("payload", event.payload());
        }
        return node;
    }

    /** Who asserted the fact. The source system name is the most honest available answer. */
    private static String actorFor(EventSource source) {
        return source == null ? "INTERNAL" : source.name();
    }

    /**
     * Map the event source onto the audit actor vocabulary
     * ({@code ck_audit_events_actor_type}).
     *
     * <p>External systems (PSP, order system, logistics, CRM) are recorded as SYSTEM rather than
     * invented as users: PDEI genuinely does not know which human, if any, was behind them, and an
     * audit trail that guesses is worse than one that says "a system did this".
     */
    private static ActorType actorTypeFor(EventSource source) {
        if (source == null) {
            return ActorType.SYSTEM;
        }
        return switch (source) {
            case MERCHANT_PORTAL -> ActorType.MERCHANT_USER;
            case SIMULATOR -> ActorType.SIMULATOR;
            case PSP_ADAPTER, ORDER_SYSTEM, LOGISTICS, CRM, INTERNAL -> ActorType.SYSTEM;
        };
    }
}
