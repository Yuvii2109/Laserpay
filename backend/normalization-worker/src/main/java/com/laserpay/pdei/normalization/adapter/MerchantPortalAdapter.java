package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.normalization.support.Payloads;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Facts a human entered in the merchant portal.
 *
 * <p>Merchants correct and supplement machine data constantly: a delivery the carrier never
 * reported, a phone conversation nobody logged, a dispute letter that arrived by post. Those facts
 * are as real as a webhook, and they must enter through the same pipeline so they get the same
 * provenance, the same audit trail and the same idempotency guarantees.
 *
 * <p>What distinguishes portal-sourced events is provenance, not shape: {@code source} is
 * {@code MERCHANT_PORTAL}, which the readiness engine can weigh differently from a carrier-signed
 * delivery confirmation, and which the AI context surfaces so an investigation never treats a
 * self-reported fact as independently verified.
 *
 * <p>File uploads are <em>not</em> handled here: those go to
 * {@code POST /api/v1/evidence} and become evidence directly through evidence-core, because they
 * carry bytes rather than a business fact.
 */
public class MerchantPortalAdapter extends AbstractSourceAdapter {

    private static final Map<String, EventType> MAPPINGS = Map.ofEntries(
            Map.entry("communication.logged", EventType.CommunicationCreated),
            Map.entry("communication.received", EventType.CommunicationReceived),
            Map.entry("delivery.confirmed", EventType.ShipmentDelivered),
            Map.entry("shipment.recorded", EventType.ShipmentCreated),
            Map.entry("shipment.dispatched", EventType.ShipmentDispatched),
            Map.entry("order.recorded", EventType.OrderCreated),
            Map.entry("order.cancelled", EventType.OrderCancelled),
            Map.entry("refund.recorded", EventType.RefundProcessed),
            Map.entry("dispute.reported", EventType.DisputeCreated));

    private static final Set<String> ALIASES = Set.of(
            "MERCHANT_PORTAL", "PORTAL", "MERCHANT", "pdei-web");

    public MerchantPortalAdapter(String defaultCurrency) {
        super("MERCHANT_PORTAL", ALIASES, MAPPINGS, defaultCurrency);
    }

    @Override
    public EventSource eventSource() {
        return EventSource.MERCHANT_PORTAL;
    }

    @Override
    protected CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt) {
        JsonNode wrapped = Payloads.first(raw.body(), "payload", "data", "form");
        JsonNode source = wrapped != null && wrapped.isObject() ? wrapped : raw.body();

        Instant occurredAt = Payloads.instantOr(source, raw.receivedAt(), "occurredAt", "occurred_at",
                "happenedAt", "eventDate", "date");
        String currency = Payloads.normalizeCurrency(
                Payloads.text(source, "currency", "currency_code"), defaultCurrency());

        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "transactionId", prefixedFrom(source, IdPrefix.TRANSACTION,
                "transactionId", "transaction_id"));
        // Portal entries are always attributable: the actor is part of the provenance story.
        Payloads.putText(payload, "enteredBy", Payloads.textOr(source, "MERCHANT_USER",
                "enteredBy", "entered_by", "actor", "user"));
        Payloads.putText(payload, "note", Payloads.text(source, "note", "comment", "remarks"));

        String aggregateId;
        switch (eventType) {
            case CommunicationCreated, CommunicationReceived -> {
                aggregateId = prefixedFrom(source, IdPrefix.COMMUNICATION, "communicationId",
                        "communication_id", "id");
                Payloads.putText(payload, "communicationId", aggregateId);
                Payloads.putText(payload, "customerId", prefixedFrom(source, IdPrefix.CUSTOMER,
                        "customerId", "customer_id"));
                Payloads.putText(payload, "channel", upper(Payloads.textOr(source, "PORTAL",
                        "channel")));
                payload.put("direction",
                        eventType == EventType.CommunicationReceived ? "INBOUND" : "OUTBOUND");
                Payloads.putText(payload, "subject", Payloads.text(source, "subject", "title"));
                Payloads.putText(payload, "bodyPreview", Payloads.text(source, "bodyPreview", "body",
                        "message"));
                Payloads.putText(payload, "objectKey", Payloads.text(source, "objectKey",
                        "object_key"));
                Payloads.putInstant(payload, "occurredAt", occurredAt);
            }
            case ShipmentCreated, ShipmentDispatched, ShipmentDelivered -> {
                aggregateId = prefixedFrom(source, IdPrefix.SHIPMENT, "shipmentId", "shipment_id",
                        "trackingNumber", "tracking_number");
                Payloads.putText(payload, "shipmentId", aggregateId);
                Payloads.putText(payload, "orderId", prefixedFrom(source, IdPrefix.ORDER, "orderId",
                        "order_id"));
                Payloads.putText(payload, "carrier", Payloads.text(source, "carrier", "courier"));
                Payloads.putText(payload, "trackingNumber", Payloads.text(source, "trackingNumber",
                        "tracking_number"));
                if (eventType == EventType.ShipmentDispatched) {
                    Payloads.putInstant(payload, "dispatchedAt", occurredAt);
                } else if (eventType == EventType.ShipmentDelivered) {
                    Payloads.putText(payload, "deliveryId", prefixedFrom(source, IdPrefix.DELIVERY,
                            "deliveryId", "delivery_id"));
                    Payloads.putText(payload, "signedBy", Payloads.text(source, "signedBy",
                            "signed_by", "receivedBy"));
                    // A merchant-attested delivery has no carrier proof artifact behind it.
                    Payloads.putText(payload, "proofType", upper(Payloads.textOr(source,
                            "MERCHANT_ATTESTED", "proofType", "proof_type")));
                    Payloads.putInstant(payload, "deliveredAt", occurredAt);
                } else {
                    Payloads.putInstant(payload, "createdAt", occurredAt);
                }
            }
            case OrderCreated, OrderCancelled -> {
                aggregateId = prefixedFrom(source, IdPrefix.ORDER, "orderId", "order_id", "id");
                Payloads.putText(payload, "orderId", aggregateId);
                Payloads.putMoney(payload, "orderTotal", Payloads.money(source, currency,
                        "orderTotal", "amount", "total"));
                if (eventType == EventType.OrderCancelled) {
                    Payloads.putText(payload, "reason", Payloads.text(source, "reason"));
                    Payloads.putText(payload, "cancelledBy", "MERCHANT");
                    Payloads.putInstant(payload, "cancelledAt", occurredAt);
                } else {
                    payload.set("lines", Payloads.array());
                    Payloads.putInstant(payload, "placedAt", occurredAt);
                }
            }
            case RefundProcessed -> {
                aggregateId = prefixedFrom(source, IdPrefix.REFUND, "refundId", "refund_id", "id");
                Payloads.putText(payload, "refundId", aggregateId);
                Payloads.putText(payload, "paymentId", prefixedFrom(source, IdPrefix.PAYMENT,
                        "paymentId", "payment_id"));
                Payloads.putMoney(payload, "amount", Payloads.money(source, currency,
                        "amount", "refundAmount"));
                Payloads.putText(payload, "settlementReference", Payloads.text(source,
                        "settlementReference", "reference"));
                Payloads.putInstant(payload, "processedAt", occurredAt);
            }
            case DisputeCreated -> {
                aggregateId = prefixedFrom(source, IdPrefix.DISPUTE, "disputeId", "dispute_id", "id");
                Payloads.putText(payload, "disputeId", aggregateId);
                String reasonCode = DisputeReasonCodes.canonical(Payloads.text(source, "reasonCode",
                        "reason_code", "reason"));
                if (reasonCode == null) {
                    throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                            "portal dispute entry has no recognisable reason code");
                }
                payload.put("reasonCode", reasonCode);
                Payloads.putText(payload, "paymentId", prefixedFrom(source, IdPrefix.PAYMENT,
                        "paymentId", "payment_id"));
                Payloads.putText(payload, "merchantId", raw.merchantId());
                Payloads.putMoney(payload, "disputedAmount", Payloads.money(source, currency,
                        "disputedAmount", "amount"));
                Payloads.putText(payload, "status", "OPEN");
                Payloads.putInstant(payload, "deadlineAt", Payloads.instant(source, "deadlineAt",
                        "deadline_at", "respondBy"));
                Payloads.putInstant(payload, "receivedAt", occurredAt);
            }
            default -> throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "MerchantPortalAdapter does not produce " + eventType);
        }

        return envelope(raw, eventType, aggregateId, occurredAt, observedAt, payload);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
