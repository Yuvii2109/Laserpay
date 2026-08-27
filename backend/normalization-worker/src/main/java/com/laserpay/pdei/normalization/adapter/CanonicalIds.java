package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.normalization.support.Payloads;

/**
 * Derives the canonical {@code aggregateId} from an already-canonical payload.
 *
 * <p>Used by the adapters whose sources already speak the canonical vocabulary (the simulator, the
 * merchant portal). Vendor adapters derive their own ids from vendor field names instead.
 *
 * <p>The aggregate id is not cosmetic: it is half the Kafka partition key
 * ({@code merchantId + ":" + aggregateId}), so it decides which events are ordered with respect to
 * each other. Getting it wrong turns an ordered stream into an interleaved one.
 */
public final class CanonicalIds {

    private CanonicalIds() {
    }

    /**
     * The identifier of the aggregate an event is about, chosen by {@link AggregateType}.
     *
     * @return the prefixed id, or {@code null} when the payload does not carry one - the caller
     *         raises {@link UnmappableEventException} rather than inventing an id
     */
    public static String forEvent(EventType eventType, JsonNode payload) {
        AggregateType aggregateType = eventType.aggregateType();
        return switch (aggregateType) {
            case PAYMENT -> prefixed(IdPrefix.PAYMENT, payload, "paymentId", "payment_id", "id");
            case ORDER -> prefixed(IdPrefix.ORDER, payload, "orderId", "order_id", "id");
            case SHIPMENT -> prefixed(IdPrefix.SHIPMENT, payload, "shipmentId", "shipment_id", "id");
            case DELIVERY -> prefixed(IdPrefix.DELIVERY, payload, "deliveryId", "delivery_id", "id");
            case REFUND -> prefixed(IdPrefix.REFUND, payload, "refundId", "refund_id", "id");
            case COMMUNICATION -> prefixed(IdPrefix.COMMUNICATION, payload, "communicationId",
                    "communication_id", "id");
            case EVIDENCE -> prefixed(IdPrefix.EVIDENCE, payload, "evidenceId", "evidence_id", "id");
            case DISPUTE -> prefixed(IdPrefix.DISPUTE, payload, "disputeId", "dispute_id", "id");
            case CASE -> prefixed(IdPrefix.CASE, payload, "caseId", "case_id", "id");
            case TRANSACTION -> prefixed(IdPrefix.TRANSACTION, payload, "transactionId",
                    "transaction_id", "id");
            case CUSTOMER -> prefixed(IdPrefix.CUSTOMER, payload, "customerId", "customer_id", "id");
            case MERCHANT -> prefixed(IdPrefix.MERCHANT, payload, "merchantId", "merchant_id", "id");
            case POLICY -> prefixed(IdPrefix.POLICY, payload, "policyId", "policy_id", "id");
        };
    }

    /**
     * The instant a canonical payload says the fact happened. Checks the per-type field first
     * ({@code capturedAt}, {@code deliveredAt}, ...) and then the generic {@code occurredAt}.
     */
    public static java.time.Instant occurredAt(EventType eventType, JsonNode payload) {
        String[] specific = switch (eventType) {
            case PaymentCreated -> new String[]{"createdAt"};
            case PaymentAuthorized -> new String[]{"authorizedAt"};
            case PaymentCaptured -> new String[]{"capturedAt"};
            case PaymentFailed -> new String[]{"failedAt"};
            case OrderCreated -> new String[]{"placedAt"};
            case OrderFulfilled -> new String[]{"fulfilledAt"};
            case OrderCancelled -> new String[]{"cancelledAt"};
            case ShipmentCreated -> new String[]{"createdAt"};
            case ShipmentDispatched -> new String[]{"dispatchedAt"};
            case ShipmentDelivered -> new String[]{"deliveredAt"};
            case RefundCreated -> new String[]{"createdAt", "requestedAt"};
            case RefundProcessed -> new String[]{"processedAt"};
            case CommunicationCreated, CommunicationReceived -> new String[]{"occurredAt", "sentAt"};
            case EvidenceAdded -> new String[]{"createdAt"};
            case EvidenceExpired -> new String[]{"expiredAt"};
            case EvidenceInvalidated -> new String[]{"invalidatedAt"};
            case DisputeCreated -> new String[]{"receivedAt", "createdAt"};
            case DisputeUpdated -> new String[]{"updatedAt"};
            case DisputeClosed -> new String[]{"closedAt"};
            default -> new String[]{"occurredAt"};
        };
        java.time.Instant fromSpecific = Payloads.instant(payload, specific);
        return fromSpecific != null
                ? fromSpecific
                : Payloads.instant(payload, "occurredAt", "occurred_at", "timestamp");
    }

    private static String prefixed(String prefix, JsonNode payload, String... paths) {
        String value = Payloads.text(payload, paths);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith(prefix) ? value : prefix + value;
    }
}
