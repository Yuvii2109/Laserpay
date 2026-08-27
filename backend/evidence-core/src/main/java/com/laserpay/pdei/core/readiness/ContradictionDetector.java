package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.util.Text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-evidence field conflicts.
 *
 * <p>A contradiction is worse than a missing document: submitting a package that contradicts itself
 * loses the dispute and damages the merchant's standing with the network. Each contradiction costs
 * 15 readiness points (platform contract 7) and, by default policy, a single one blocks automated
 * preparation entirely ({@code maxContradictions = 0}).</p>
 *
 * <p>Every rule is deterministic and explainable: it names the two records that disagree, the field
 * they disagree on, and both values. Nothing here is probabilistic - the model is never asked
 * whether two dates conflict.</p>
 *
 * <p>Rules implemented:</p>
 * <ol>
 *   <li>{@code deliveredAt} earlier than the shipment {@code dispatchedAt} - HIGH</li>
 *   <li>delivery recorded for a shipment that was never dispatched - HIGH</li>
 *   <li>{@code deliveredAt} earlier than the order {@code createdAt} - HIGH</li>
 *   <li>refunded total greater than captured total - CRITICAL</li>
 *   <li>a single refund greater than the payment it refunds - CRITICAL</li>
 *   <li>refund currency different from the payment currency - CRITICAL</li>
 *   <li>delivery address different from the order shipping address - HIGH</li>
 *   <li>shipment destination different from the order shipping address - MEDIUM</li>
 *   <li>shipped quantity different from ordered quantity - MEDIUM</li>
 *   <li>captured amount different from the order total - MEDIUM</li>
 * </ol>
 */
public class ContradictionDetector {

    public static final String FIELD_DELIVERED_AT = "deliveredAt";
    public static final String FIELD_DISPATCHED_AT = "dispatchedAt";
    public static final String FIELD_REFUND_AMOUNT = "refundAmount";
    public static final String FIELD_CURRENCY = "currency";
    public static final String FIELD_DELIVERY_ADDRESS = "deliveryAddress";
    public static final String FIELD_QUANTITY = "quantity";
    public static final String FIELD_AMOUNT_MINOR = "amountMinor";

    /** Detect contradictions in a transaction, attributing each side to evidence where possible. */
    public List<ContradictionView> detect(TransactionFacts facts, List<EvidenceView> evidence, Instant now) {
        List<ContradictionView> found = new ArrayList<>();
        if (facts == null) {
            return List.of();
        }
        Map<String, String> evidenceByEntity = indexEvidenceByEntity(evidence);

        Map<String, TransactionFacts.ShipmentFact> shipments = new HashMap<>();
        facts.shipments().forEach(shipment -> shipments.put(shipment.shipmentId(), shipment));
        Map<String, TransactionFacts.OrderFact> orders = new HashMap<>();
        facts.orders().forEach(order -> orders.put(order.orderId(), order));
        Map<String, TransactionFacts.PaymentFact> payments = new HashMap<>();
        facts.payments().forEach(payment -> payments.put(payment.paymentId(), payment));

        detectDeliveryTiming(facts, shipments, orders, evidenceByEntity, now, found);
        detectRefundAmounts(facts, payments, evidenceByEntity, now, found);
        detectAddressMismatch(facts, shipments, orders, evidenceByEntity, now, found);
        detectQuantityMismatch(facts, shipments, orders, evidenceByEntity, now, found);
        detectAmountMismatch(facts, evidenceByEntity, now, found);
        return List.copyOf(found);
    }

    public List<ContradictionView> detect(TransactionFacts facts, List<EvidenceView> evidence) {
        return detect(facts, evidence, Instant.now());
    }

    // --- rules 1 to 3 ---------------------------------------------------------------------------

    private void detectDeliveryTiming(TransactionFacts facts,
                                      Map<String, TransactionFacts.ShipmentFact> shipments,
                                      Map<String, TransactionFacts.OrderFact> orders,
                                      Map<String, String> evidenceByEntity, Instant now,
                                      List<ContradictionView> found) {
        for (TransactionFacts.DeliveryFact delivery : facts.deliveries()) {
            if (delivery.deliveredAt() == null) {
                continue;
            }
            TransactionFacts.ShipmentFact shipment = shipments.get(delivery.shipmentId());
            if (shipment != null) {
                if (shipment.dispatchedAt() != null
                        && delivery.deliveredAt().isBefore(shipment.dispatchedAt())) {
                    found.add(ContradictionView.of(
                            ref(evidenceByEntity, delivery.deliveryId()),
                            ref(evidenceByEntity, shipment.shipmentId()),
                            FIELD_DELIVERED_AT,
                            "delivery " + delivery.deliveryId() + " is recorded before shipment "
                                    + shipment.shipmentId() + " was dispatched",
                            GapSeverity.HIGH, delivery.deliveredAt(), shipment.dispatchedAt(), now));
                } else if (shipment.dispatchedAt() == null) {
                    found.add(ContradictionView.of(
                            ref(evidenceByEntity, delivery.deliveryId()),
                            ref(evidenceByEntity, shipment.shipmentId()),
                            FIELD_DISPATCHED_AT,
                            "delivery " + delivery.deliveryId() + " exists but shipment "
                                    + shipment.shipmentId() + " has no dispatch timestamp",
                            GapSeverity.HIGH, delivery.deliveredAt(), null, now));
                }
                TransactionFacts.OrderFact order = orders.get(shipment.orderId());
                if (order != null && order.createdAt() != null
                        && delivery.deliveredAt().isBefore(order.createdAt())) {
                    found.add(ContradictionView.of(
                            ref(evidenceByEntity, delivery.deliveryId()),
                            ref(evidenceByEntity, order.orderId()),
                            FIELD_DELIVERED_AT,
                            "delivery " + delivery.deliveryId() + " predates order " + order.orderId(),
                            GapSeverity.HIGH, delivery.deliveredAt(), order.createdAt(), now));
                }
            }
        }
    }

    // --- rules 4 to 6 ---------------------------------------------------------------------------

    private void detectRefundAmounts(TransactionFacts facts,
                                     Map<String, TransactionFacts.PaymentFact> payments,
                                     Map<String, String> evidenceByEntity, Instant now,
                                     List<ContradictionView> found) {
        for (TransactionFacts.RefundFact refund : facts.refunds()) {
            TransactionFacts.PaymentFact payment = payments.get(refund.paymentId());
            if (payment == null || refund.amount() == null || payment.amount() == null) {
                continue;
            }
            if (!sameCurrency(refund.amount(), payment.amount())) {
                found.add(ContradictionView.of(
                        ref(evidenceByEntity, refund.refundId()),
                        ref(evidenceByEntity, payment.paymentId()),
                        FIELD_CURRENCY,
                        "refund " + refund.refundId() + " is in a different currency than payment "
                                + payment.paymentId(),
                        GapSeverity.CRITICAL, refund.amount().currency(), payment.amount().currency(), now));
                continue;
            }
            if (refund.amount().amountMinor() > payment.amount().amountMinor()) {
                found.add(ContradictionView.of(
                        ref(evidenceByEntity, refund.refundId()),
                        ref(evidenceByEntity, payment.paymentId()),
                        FIELD_REFUND_AMOUNT,
                        "refund " + refund.refundId() + " exceeds the amount captured on payment "
                                + payment.paymentId(),
                        GapSeverity.CRITICAL, refund.amount().amountMinor(),
                        payment.amount().amountMinor(), now));
            }
        }
        Money captured = safeTotal(facts::capturedTotal);
        Money refunded = safeTotal(facts::refundedTotal);
        if (captured != null && refunded != null && sameCurrency(captured, refunded)
                && refunded.amountMinor() > captured.amountMinor()) {
            found.add(ContradictionView.of(
                    facts.transactionId(), facts.transactionId(), FIELD_REFUND_AMOUNT,
                    "total refunded exceeds total captured on transaction " + facts.transactionId(),
                    GapSeverity.CRITICAL, refunded.amountMinor(), captured.amountMinor(), now));
        }
    }

    // --- rules 7 and 8 --------------------------------------------------------------------------

    private void detectAddressMismatch(TransactionFacts facts,
                                       Map<String, TransactionFacts.ShipmentFact> shipments,
                                       Map<String, TransactionFacts.OrderFact> orders,
                                       Map<String, String> evidenceByEntity, Instant now,
                                       List<ContradictionView> found) {
        for (TransactionFacts.DeliveryFact delivery : facts.deliveries()) {
            TransactionFacts.ShipmentFact shipment = shipments.get(delivery.shipmentId());
            if (shipment == null) {
                continue;
            }
            TransactionFacts.OrderFact order = orders.get(shipment.orderId());
            if (order == null) {
                continue;
            }
            if (!Text.sameAddress(delivery.deliveredToAddress(), order.shippingAddress())) {
                found.add(ContradictionView.of(
                        ref(evidenceByEntity, delivery.deliveryId()),
                        ref(evidenceByEntity, order.orderId()),
                        FIELD_DELIVERY_ADDRESS,
                        "delivery " + delivery.deliveryId() + " was signed for at an address that does"
                                + " not match the shipping address on order " + order.orderId(),
                        GapSeverity.HIGH, delivery.deliveredToAddress(), order.shippingAddress(), now));
            }
        }
        for (TransactionFacts.ShipmentFact shipment : facts.shipments()) {
            TransactionFacts.OrderFact order = orders.get(shipment.orderId());
            if (order == null) {
                continue;
            }
            if (!Text.sameAddress(shipment.destinationAddress(), order.shippingAddress())) {
                found.add(ContradictionView.of(
                        ref(evidenceByEntity, shipment.shipmentId()),
                        ref(evidenceByEntity, order.orderId()),
                        FIELD_DELIVERY_ADDRESS,
                        "shipment " + shipment.shipmentId() + " is addressed differently from order "
                                + order.orderId(),
                        GapSeverity.MEDIUM, shipment.destinationAddress(), order.shippingAddress(), now));
            }
        }
    }

    // --- rule 9 ---------------------------------------------------------------------------------

    private void detectQuantityMismatch(TransactionFacts facts,
                                        Map<String, TransactionFacts.ShipmentFact> shipments,
                                        Map<String, TransactionFacts.OrderFact> orders,
                                        Map<String, String> evidenceByEntity, Instant now,
                                        List<ContradictionView> found) {
        Map<String, Integer> shippedByOrder = new HashMap<>();
        for (TransactionFacts.ShipmentFact shipment : shipments.values()) {
            if (shipment.orderId() == null || shipment.quantity() <= 0) {
                continue;
            }
            shippedByOrder.merge(shipment.orderId(), shipment.quantity(), Integer::sum);
        }
        for (TransactionFacts.OrderFact order : orders.values()) {
            int ordered = order.totalQuantity();
            Integer shipped = shippedByOrder.get(order.orderId());
            if (ordered <= 0 || shipped == null) {
                // No line detail or no per-shipment quantity: absence of data is a provenance gap,
                // handled by GapDetector, not a contradiction.
                continue;
            }
            if (shipped != ordered) {
                found.add(ContradictionView.of(
                        ref(evidenceByEntity, order.orderId()),
                        facts.transactionId(),
                        FIELD_QUANTITY,
                        "order " + order.orderId() + " has " + ordered + " units but " + shipped
                                + " were shipped",
                        GapSeverity.MEDIUM, ordered, shipped, now));
            }
        }
    }

    // --- rule 10 --------------------------------------------------------------------------------

    private void detectAmountMismatch(TransactionFacts facts, Map<String, String> evidenceByEntity,
                                      Instant now, List<ContradictionView> found) {
        Money captured = safeTotal(facts::capturedTotal);
        if (captured == null || facts.orders().size() != 1) {
            return;
        }
        TransactionFacts.OrderFact order = facts.orders().get(0);
        if (order.total() == null || !sameCurrency(order.total(), captured)) {
            return;
        }
        if (order.total().amountMinor() != captured.amountMinor()) {
            found.add(ContradictionView.of(
                    ref(evidenceByEntity, order.orderId()),
                    facts.transactionId(),
                    FIELD_AMOUNT_MINOR,
                    "order " + order.orderId() + " totals " + order.total().amountMinor()
                            + " minor units but " + captured.amountMinor() + " was captured",
                    GapSeverity.MEDIUM, order.total().amountMinor(), captured.amountMinor(), now));
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Prefer the evidence id that documents an entity; fall back to the entity id itself. */
    private static String ref(Map<String, String> evidenceByEntity, String entityId) {
        String evidenceId = evidenceByEntity.get(entityId);
        return evidenceId != null ? evidenceId : entityId;
    }

    private static Map<String, String> indexEvidenceByEntity(List<EvidenceView> evidence) {
        Map<String, String> index = new HashMap<>();
        if (evidence == null) {
            return index;
        }
        for (EvidenceView view : evidence) {
            if (view.relatedEntityId() != null && view.isUsable()) {
                index.putIfAbsent(view.relatedEntityId(), view.evidenceId());
            }
        }
        return index;
    }

    private static boolean sameCurrency(Money left, Money right) {
        return left != null && right != null && left.currency() != null
                && left.currency().equalsIgnoreCase(right.currency());
    }

    /**
     * Money totals throw on a currency mismatch. A mixed-currency transaction is itself reported as
     * a contradiction elsewhere, so here we degrade to "no total available" instead of failing the
     * whole readiness computation.
     */
    private static Money safeTotal(java.util.function.Supplier<Money> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
