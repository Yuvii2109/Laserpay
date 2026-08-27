package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.event.EventType;

/**
 * The source-system vocabulary the simulator speaks on {@code pdei.raw.events.v1}.
 *
 * <p>Raw events are "source-shaped" by contract (platform contract 4): they carry the words the
 * originating system uses, not PDEI's canonical {@link EventType} names, and turning one into
 * the other is normalization-worker's entire job. A simulator that published canonical events
 * directly would quietly skip the layer it exists to exercise.
 *
 * <p>So this class is the mapping table normalization-worker must implement, expressed once,
 * here, as the simulator's outbound contract. Five source systems, matching the
 * {@code EventSource} enum PDEI already knows about:
 *
 * <pre>
 * psp-adapter    payment_intent.*        -&gt; PAYMENT / DISPUTE
 * order-system   order.*                 -&gt; ORDER
 * logistics      shipment.*              -&gt; SHIPMENT
 * crm            message.*               -&gt; COMMUNICATION
 * merchant-portal document.uploaded      -&gt; EVIDENCE
 * </pre>
 *
 * <p>The {@code pdei-event-type} header on every envelope carries the expected canonical name as
 * a hint. It is a hint and not the contract: normalization-worker is free to derive the type from
 * {@code sourceEventType} and ignore it entirely, which is what it will do for real webhooks.
 */
public final class SourceVocabulary {

    public static final String PSP = "psp-adapter";
    public static final String ORDER_SYSTEM = "order-system";
    public static final String LOGISTICS = "logistics";
    public static final String CRM = "crm";
    public static final String MERCHANT_PORTAL = "merchant-portal";

    private SourceVocabulary() {
    }

    /** The source system that would emit this canonical event. */
    public static String systemFor(EventType type) {
        return switch (type) {
            case PaymentCreated, PaymentAuthorized, PaymentCaptured, PaymentFailed,
                 RefundCreated, RefundProcessed,
                 DisputeCreated, DisputeUpdated, DisputeClosed -> PSP;
            case OrderCreated, OrderFulfilled, OrderCancelled -> ORDER_SYSTEM;
            case ShipmentCreated, ShipmentDispatched, ShipmentDelivered -> LOGISTICS;
            case CommunicationCreated, CommunicationReceived -> CRM;
            case EvidenceAdded, EvidenceExpired, EvidenceInvalidated -> MERCHANT_PORTAL;
            default -> throw new IllegalArgumentException(
                    "the simulator does not generate internal event type " + type);
        };
    }

    /** The source system's own name for this fact. */
    public static String sourceEventType(EventType type) {
        return switch (type) {
            case PaymentCreated -> "payment_intent.created";
            case PaymentAuthorized -> "payment_intent.authorized";
            case PaymentCaptured -> "payment_intent.succeeded";
            case PaymentFailed -> "payment_intent.payment_failed";
            case RefundCreated -> "refund.created";
            case RefundProcessed -> "refund.succeeded";
            case DisputeCreated -> "charge.dispute.created";
            case DisputeUpdated -> "charge.dispute.updated";
            case DisputeClosed -> "charge.dispute.closed";
            case OrderCreated -> "order.created";
            case OrderFulfilled -> "order.fulfilled";
            case OrderCancelled -> "order.cancelled";
            case ShipmentCreated -> "shipment.label_created";
            case ShipmentDispatched -> "shipment.in_transit";
            case ShipmentDelivered -> "shipment.delivered";
            case CommunicationCreated -> "message.sent";
            case CommunicationReceived -> "message.received";
            case EvidenceAdded -> "document.uploaded";
            case EvidenceExpired -> "document.expired";
            case EvidenceInvalidated -> "document.invalidated";
            default -> throw new IllegalArgumentException(
                    "the simulator does not generate internal event type " + type);
        };
    }
}
