package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.money.Money;

import java.time.Instant;
import java.util.List;

/**
 * Flattened, immutable projection of every financial fact attached to one transaction.
 *
 * <p>This is the input of {@code ContradictionDetector}, {@code EvidenceGraphService} and
 * {@code TimelineService}. It exists so those three are pure functions over data and can be unit
 * tested without a database.</p>
 */
public record TransactionFacts(
        String transactionId,
        String merchantId,
        String customerId,
        Money amount,
        String status,
        Instant createdAt,
        List<PaymentFact> payments,
        List<OrderFact> orders,
        List<ShipmentFact> shipments,
        List<DeliveryFact> deliveries,
        List<RefundFact> refunds,
        List<CommunicationFact> communications) {

    public TransactionFacts {
        payments = payments == null ? List.of() : List.copyOf(payments);
        orders = orders == null ? List.of() : List.copyOf(orders);
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
        communications = communications == null ? List.of() : List.copyOf(communications);
    }

    public static TransactionFacts empty(String transactionId, String merchantId) {
        return new TransactionFacts(transactionId, merchantId, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Total captured across all payments, or {@code null} when nothing was captured. */
    public Money capturedTotal() {
        Money total = null;
        for (PaymentFact payment : payments) {
            if (payment.capturedAt() == null || payment.amount() == null) {
                continue;
            }
            total = total == null ? payment.amount() : total.plus(payment.amount());
        }
        return total;
    }

    /** Total refunded across all refunds, or {@code null} when nothing was refunded. */
    public Money refundedTotal() {
        Money total = null;
        for (RefundFact refund : refunds) {
            if (refund.amount() == null) {
                continue;
            }
            total = total == null ? refund.amount() : total.plus(refund.amount());
        }
        return total;
    }

    public record PaymentFact(String paymentId, String status, Money amount, String processorReference,
                              Instant createdAt, Instant authorizedAt, Instant capturedAt,
                              String avsResult, String cvvResult) {
    }

    public record OrderFact(String orderId, String status, Money total, String shippingAddress,
                            Instant createdAt, Instant fulfilledAt, List<OrderLineFact> lines) {
        public OrderFact {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public int totalQuantity() {
            return lines.stream().mapToInt(OrderLineFact::quantity).sum();
        }
    }

    public record OrderLineFact(String lineId, String sku, String description, int quantity, Money unitPrice) {
    }

    public record ShipmentFact(String shipmentId, String orderId, String carrier, String trackingNumber,
                               String status, String destinationAddress, int quantity,
                               Instant createdAt, Instant dispatchedAt) {
    }

    public record DeliveryFact(String deliveryId, String shipmentId, String status, String signedBy,
                               String deliveredToAddress, String proofType, Instant deliveredAt) {
    }

    public record RefundFact(String refundId, String paymentId, String status, Money amount,
                             Instant createdAt, Instant processedAt) {
    }

    public record CommunicationFact(String communicationId, String channel, String direction,
                                    String subject, String body, Instant occurredAt) {
    }
}
