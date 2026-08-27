package com.laserpay.pdei.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.laserpay.pdei.common.error.UnknownEventTypeException;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Canonical event vocabulary (PLATFORM-CONTRACT section 3.1).
 *
 * <p>Constant names are the wire values <em>verbatim</em> - PascalCase, not SCREAMING_SNAKE - so
 * that Java, Python and TypeScript can all match on the identical string. Do not rename.
 *
 * <p>Each constant declares the {@link AggregateType} it is about, which lets normalization-worker
 * derive {@code aggregateType} rather than trusting a source system to supply it.
 */
public enum EventType {

    // --- PAYMENT ---------------------------------------------------------------------------
    PaymentCreated(AggregateType.PAYMENT),
    PaymentAuthorized(AggregateType.PAYMENT),
    PaymentCaptured(AggregateType.PAYMENT),
    PaymentFailed(AggregateType.PAYMENT),

    // --- ORDER -----------------------------------------------------------------------------
    OrderCreated(AggregateType.ORDER),
    OrderFulfilled(AggregateType.ORDER),
    OrderCancelled(AggregateType.ORDER),

    // --- SHIPMENT --------------------------------------------------------------------------
    ShipmentCreated(AggregateType.SHIPMENT),
    ShipmentDispatched(AggregateType.SHIPMENT),
    ShipmentDelivered(AggregateType.SHIPMENT),

    // --- REFUND ----------------------------------------------------------------------------
    RefundCreated(AggregateType.REFUND),
    RefundProcessed(AggregateType.REFUND),

    // --- COMMUNICATION ---------------------------------------------------------------------
    CommunicationCreated(AggregateType.COMMUNICATION),
    CommunicationReceived(AggregateType.COMMUNICATION),

    // --- EVIDENCE --------------------------------------------------------------------------
    EvidenceAdded(AggregateType.EVIDENCE),
    EvidenceExpired(AggregateType.EVIDENCE),
    EvidenceInvalidated(AggregateType.EVIDENCE),

    // --- DISPUTE ---------------------------------------------------------------------------
    DisputeCreated(AggregateType.DISPUTE),
    DisputeUpdated(AggregateType.DISPUTE),
    DisputeClosed(AggregateType.DISPUTE),

    // --- READINESS (internal, produced by readiness-worker) --------------------------------
    // Readiness is always computed for a transaction, so the aggregate is the transaction.
    ReadinessRecomputed(AggregateType.TRANSACTION, true),
    ReadinessGapDetected(AggregateType.TRANSACTION, true),

    // --- CASE (internal, produced by case-orchestrator-service) ----------------------------
    CaseOpened(AggregateType.CASE, true),
    CaseEvidenceAttached(AggregateType.CASE, true),
    CaseInvestigated(AggregateType.CASE, true),
    CasePrepared(AggregateType.CASE, true),
    CaseEscalated(AggregateType.CASE, true),
    CaseSubmitted(AggregateType.CASE, true),
    CaseClosed(AggregateType.CASE, true),

    // --- AUDIT (internal) -------------------------------------------------------------------
    // Audit records are merchant-scoped; the concrete subject is carried by
    // AuditEvent.entityType / AuditEvent.entityId rather than by the aggregate.
    AuditRecorded(AggregateType.MERCHANT, true);

    private static final Map<String, EventType> BY_WIRE_NAME = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(EventType::name, Function.identity()));

    private final AggregateType aggregateType;
    private final boolean internal;

    EventType(AggregateType aggregateType) {
        this(aggregateType, false);
    }

    EventType(AggregateType aggregateType, boolean internal) {
        this.aggregateType = aggregateType;
        this.internal = internal;
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    /** True for event types PDEI produces itself rather than ingesting from an external system. */
    public boolean isInternal() {
        return internal;
    }

    public boolean isEvidenceEvent() {
        return aggregateType == AggregateType.EVIDENCE;
    }

    public boolean isDisputeEvent() {
        return aggregateType == AggregateType.DISPUTE;
    }

    public boolean isCaseEvent() {
        return aggregateType == AggregateType.CASE;
    }

    public boolean isReadinessEvent() {
        return this == ReadinessRecomputed || this == ReadinessGapDetected;
    }

    public boolean isAuditEvent() {
        return this == AuditRecorded;
    }

    /** The wire value: identical to {@link #name()}. */
    @JsonValue
    public String wireName() {
        return name();
    }

    /**
     * Exact-name lookup.
     *
     * @throws UnknownEventTypeException when the value is unknown to this build; consumers
     *         dead-letter such records instead of failing the partition.
     */
    @JsonCreator
    public static EventType fromWire(String s) {
        EventType type = s == null ? null : BY_WIRE_NAME.get(s);
        if (type == null) {
            throw new UnknownEventTypeException(s);
        }
        return type;
    }
}
