package com.laserpay.pdei.core.evidence;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceEdge;
import com.laserpay.pdei.core.model.EvidenceGraph;
import com.laserpay.pdei.core.model.EvidenceNode;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.readiness.ContradictionDetector;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import com.laserpay.pdei.core.util.CoreErrors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the node/edge projection of a transaction shown on
 * {@code GET /api/v1/transactions/{transactionId}/graph} and in the Case X-Ray graph tab.
 *
 * <p>The graph is derived on read from relational state - there is no graph database. That is a
 * deliberate choice: the shapes are small (tens of nodes per transaction) and keeping one source of
 * truth is worth more than traversal speed we do not need.</p>
 *
 * <p>Contradiction edges are added last, so the UI can render in one picture both what the merchant
 * has and where their own records disagree with each other.</p>
 */
public class EvidenceGraphService {

    private final TransactionRepositoryPort transactions;
    private final EvidenceRepositoryPort evidence;
    private final ContradictionDetector contradictionDetector;
    private final Clocks clock;

    public EvidenceGraphService(TransactionRepositoryPort transactions, EvidenceRepositoryPort evidence,
                                ContradictionDetector contradictionDetector, Clocks clock) {
        this.transactions = transactions;
        this.evidence = evidence;
        this.contradictionDetector = contradictionDetector;
        this.clock = clock;
    }

    public EvidenceGraph build(String transactionId) {
        CoreErrors.requireText(transactionId, "transactionId");
        TransactionFacts facts = transactions.findFacts(transactionId)
                .orElseThrow(() -> CoreErrors.notFound("transaction", transactionId));
        List<EvidenceView> artifacts = evidence.findByTransactionId(transactionId);
        List<ContradictionView> contradictions = contradictionDetector.detect(facts, artifacts, clock.now());
        return build(facts, artifacts, contradictions);
    }

    /** Pure builder - used by tests and by case assembly, which already has the facts loaded. */
    public EvidenceGraph build(TransactionFacts facts, List<EvidenceView> artifacts,
                               List<ContradictionView> contradictions) {
        Map<String, EvidenceNode> nodes = new LinkedHashMap<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        String txId = facts.transactionId();

        put(nodes, new EvidenceNode(txId, AggregateType.TRANSACTION, "Transaction " + txId,
                facts.status(), facts.createdAt(),
                attributes("amount", money(facts.amount()), "customerId", facts.customerId())));

        for (TransactionFacts.PaymentFact payment : facts.payments()) {
            put(nodes, new EvidenceNode(payment.paymentId(), AggregateType.PAYMENT,
                    "Payment " + payment.paymentId(), payment.status(), payment.capturedAt(),
                    attributes("amount", money(payment.amount()),
                            "processorReference", payment.processorReference())));
            edge(edges, edgeKeys, txId, payment.paymentId(), EvidenceEdge.HAS_PAYMENT);
        }

        for (TransactionFacts.OrderFact order : facts.orders()) {
            put(nodes, new EvidenceNode(order.orderId(), AggregateType.ORDER, "Order " + order.orderId(),
                    order.status(), order.createdAt(),
                    attributes("total", money(order.total()), "quantity", order.totalQuantity(),
                            "shippingAddress", order.shippingAddress())));
            edge(edges, edgeKeys, txId, order.orderId(), EvidenceEdge.HAS_ORDER);
        }

        for (TransactionFacts.ShipmentFact shipment : facts.shipments()) {
            put(nodes, new EvidenceNode(shipment.shipmentId(), AggregateType.SHIPMENT,
                    "Shipment " + shipment.shipmentId(), shipment.status(), shipment.dispatchedAt(),
                    attributes("carrier", shipment.carrier(), "trackingNumber", shipment.trackingNumber(),
                            "quantity", shipment.quantity())));
            String parent = nodes.containsKey(shipment.orderId()) ? shipment.orderId() : txId;
            edge(edges, edgeKeys, parent, shipment.shipmentId(), EvidenceEdge.SHIPPED_AS);
        }

        for (TransactionFacts.DeliveryFact delivery : facts.deliveries()) {
            put(nodes, new EvidenceNode(delivery.deliveryId(), AggregateType.DELIVERY,
                    "Delivery " + delivery.deliveryId(), delivery.status(), delivery.deliveredAt(),
                    attributes("signedBy", delivery.signedBy(), "proofType", delivery.proofType(),
                            "address", delivery.deliveredToAddress())));
            String parent = nodes.containsKey(delivery.shipmentId()) ? delivery.shipmentId() : txId;
            edge(edges, edgeKeys, parent, delivery.deliveryId(), EvidenceEdge.DELIVERED_AS);
        }

        for (TransactionFacts.RefundFact refund : facts.refunds()) {
            put(nodes, new EvidenceNode(refund.refundId(), AggregateType.REFUND,
                    "Refund " + refund.refundId(), refund.status(), refund.processedAt(),
                    attributes("amount", money(refund.amount()))));
            edge(edges, edgeKeys, txId, refund.refundId(), EvidenceEdge.HAS_REFUND);
            if (nodes.containsKey(refund.paymentId())) {
                edge(edges, edgeKeys, refund.refundId(), refund.paymentId(), EvidenceEdge.REFUNDS);
            }
        }

        for (TransactionFacts.CommunicationFact communication : facts.communications()) {
            put(nodes, new EvidenceNode(communication.communicationId(), AggregateType.COMMUNICATION,
                    communication.channel() + " " + communication.direction(), null,
                    communication.occurredAt(), attributes("subject", communication.subject())));
            edge(edges, edgeKeys, txId, communication.communicationId(), EvidenceEdge.HAS_COMMUNICATION);
        }

        for (EvidenceView view : artifacts) {
            put(nodes, new EvidenceNode(view.evidenceId(), AggregateType.EVIDENCE,
                    view.type() + " v" + view.version(), String.valueOf(view.status()), view.createdAt(),
                    attributes("sha256", view.sha256(), "source", String.valueOf(view.source()),
                            "filename", view.filename())));
            String target = view.relatedEntityId() != null && nodes.containsKey(view.relatedEntityId())
                    ? view.relatedEntityId() : txId;
            edge(edges, edgeKeys, view.evidenceId(), target, EvidenceEdge.EVIDENCES);
            if (view.parentEvidenceId() != null) {
                edge(edges, edgeKeys, view.evidenceId(), view.parentEvidenceId(), EvidenceEdge.SUPERSEDES);
            }
        }

        if (contradictions != null) {
            for (ContradictionView contradiction : contradictions) {
                if (contradiction.left() == null || contradiction.right() == null
                        || !nodes.containsKey(contradiction.left())
                        || !nodes.containsKey(contradiction.right())) {
                    continue;
                }
                edges.add(new EvidenceEdge(contradiction.left(), contradiction.right(),
                        EvidenceEdge.CONTRADICTS,
                        attributes("field", contradiction.field(), "detail", contradiction.detail(),
                                "severity", String.valueOf(contradiction.severity()))));
            }
        }

        return new EvidenceGraph(txId, List.copyOf(nodes.values()), edges, clock.now());
    }

    private static void put(Map<String, EvidenceNode> nodes, EvidenceNode node) {
        if (node.id() != null) {
            nodes.putIfAbsent(node.id(), node);
        }
    }

    private static void edge(List<EvidenceEdge> edges, Set<String> keys, String from, String to,
                             String relation) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        if (keys.add(from + ">" + to + ">" + relation)) {
            edges.add(EvidenceEdge.of(from, to, relation));
        }
    }

    private static Map<String, Object> attributes(Object... pairs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                attributes.put(String.valueOf(pairs[i]), value);
            }
        }
        return attributes;
    }

    /** Money crosses the wire as minor units plus currency, never as a formatted or floating value. */
    private static Map<String, Object> money(Money money) {
        return money == null ? null : Map.of("amountMinor", money.amountMinor(), "currency", money.currency());
    }
}
