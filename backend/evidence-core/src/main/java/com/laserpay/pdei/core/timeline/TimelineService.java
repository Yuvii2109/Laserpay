package com.laserpay.pdei.core.timeline;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TimelineEntry;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import com.laserpay.pdei.core.util.CoreErrors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The unified event + evidence timeline behind
 * {@code GET /api/v1/transactions/{transactionId}/timeline}, the transaction detail screen and the
 * {@code timeline} field of {@link com.laserpay.pdei.core.model.InvestigationContext}.
 *
 * <p>Entries are derived from current state rather than replayed from Kafka. The event log is a
 * transport, not a queryable history, and state-builder-worker has already folded every event into
 * the entities this reads. That keeps the timeline correct in the face of late and out-of-order
 * delivery: whatever order the events arrived in, the timeline is sorted by when things happened.</p>
 *
 * <p>Entries with no timestamp are dropped rather than guessed. A missing timestamp is a provenance
 * gap for {@code GapDetector} to report, not something to invent a position for.</p>
 */
public class TimelineService {

    private final TransactionRepositoryPort transactions;
    private final EvidenceRepositoryPort evidence;
    private final CaseRepositoryPort cases;

    public TimelineService(TransactionRepositoryPort transactions, EvidenceRepositoryPort evidence,
                           CaseRepositoryPort cases) {
        this.transactions = transactions;
        this.evidence = evidence;
        this.cases = cases;
    }

    public List<TimelineEntry> timeline(String transactionId) {
        CoreErrors.requireText(transactionId, "transactionId");
        TransactionFacts facts = transactions.findFacts(transactionId)
                .orElseThrow(() -> CoreErrors.notFound("transaction", transactionId));
        List<EvidenceView> artifacts = evidence.findByTransactionId(transactionId);
        DisputeView dispute = cases == null ? null
                : cases.findOpenDisputeForTransaction(transactionId).orElse(null);
        return build(facts, artifacts, dispute);
    }

    /** Pure builder, so the ordering rules can be tested without a database. */
    public List<TimelineEntry> build(TransactionFacts facts, List<EvidenceView> artifacts,
                                     DisputeView dispute) {
        List<TimelineEntry> entries = new ArrayList<>();

        add(entries, facts.createdAt(), "TransactionCreated", AggregateType.TRANSACTION,
                facts.transactionId(), "Transaction opened", "INTERNAL", Map.of());

        for (TransactionFacts.PaymentFact payment : facts.payments()) {
            add(entries, payment.createdAt(), EventType.PaymentCreated.name(), AggregateType.PAYMENT,
                    payment.paymentId(), "Payment created", "PSP_ADAPTER", Map.of());
            add(entries, payment.authorizedAt(), EventType.PaymentAuthorized.name(), AggregateType.PAYMENT,
                    payment.paymentId(), "Payment authorized", "PSP_ADAPTER", Map.of());
            add(entries, payment.capturedAt(), EventType.PaymentCaptured.name(), AggregateType.PAYMENT,
                    payment.paymentId(), "Payment captured", "PSP_ADAPTER", money(payment.amount()));
        }

        for (TransactionFacts.OrderFact order : facts.orders()) {
            add(entries, order.createdAt(), EventType.OrderCreated.name(), AggregateType.ORDER,
                    order.orderId(), "Order placed", "ORDER_SYSTEM",
                    Map.of("quantity", order.totalQuantity()));
            add(entries, order.fulfilledAt(), EventType.OrderFulfilled.name(), AggregateType.ORDER,
                    order.orderId(), "Order fulfilled", "ORDER_SYSTEM", Map.of());
        }

        for (TransactionFacts.ShipmentFact shipment : facts.shipments()) {
            add(entries, shipment.createdAt(), EventType.ShipmentCreated.name(), AggregateType.SHIPMENT,
                    shipment.shipmentId(), "Shipment created", "LOGISTICS", Map.of());
            add(entries, shipment.dispatchedAt(), EventType.ShipmentDispatched.name(),
                    AggregateType.SHIPMENT, shipment.shipmentId(),
                    "Dispatched via " + shipment.carrier(), "LOGISTICS",
                    shipment.trackingNumber() == null ? Map.of()
                            : Map.of("trackingNumber", shipment.trackingNumber()));
        }

        for (TransactionFacts.DeliveryFact delivery : facts.deliveries()) {
            add(entries, delivery.deliveredAt(), EventType.ShipmentDelivered.name(),
                    AggregateType.DELIVERY, delivery.deliveryId(),
                    delivery.signedBy() == null ? "Delivered" : "Delivered, signed by " + delivery.signedBy(),
                    "LOGISTICS", Map.of());
        }

        for (TransactionFacts.RefundFact refund : facts.refunds()) {
            add(entries, refund.createdAt(), EventType.RefundCreated.name(), AggregateType.REFUND,
                    refund.refundId(), "Refund created", "PSP_ADAPTER", Map.of());
            add(entries, refund.processedAt(), EventType.RefundProcessed.name(), AggregateType.REFUND,
                    refund.refundId(), "Refund processed", "PSP_ADAPTER", money(refund.amount()));
        }

        for (TransactionFacts.CommunicationFact communication : facts.communications()) {
            String eventType = "INBOUND".equalsIgnoreCase(communication.direction())
                    ? EventType.CommunicationReceived.name() : EventType.CommunicationCreated.name();
            add(entries, communication.occurredAt(), eventType, AggregateType.COMMUNICATION,
                    communication.communicationId(),
                    communication.channel() + ": " + communication.subject(), "CRM", Map.of());
        }

        for (EvidenceView view : artifacts) {
            add(entries, view.createdAt(), EventType.EvidenceAdded.name(), AggregateType.EVIDENCE,
                    view.evidenceId(), view.type() + " v" + view.version() + " captured",
                    String.valueOf(view.source()),
                    Map.of("sha256", String.valueOf(view.sha256()),
                            "status", String.valueOf(view.status())));
        }

        if (dispute != null) {
            add(entries, dispute.openedAt(), EventType.DisputeCreated.name(), AggregateType.DISPUTE,
                    dispute.disputeId(), "Dispute raised: " + dispute.reasonCode(), "PSP_ADAPTER",
                    money(dispute.amount()));
            add(entries, dispute.closedAt(), EventType.DisputeClosed.name(), AggregateType.DISPUTE,
                    dispute.disputeId(), "Dispute closed: " + dispute.status(), "PSP_ADAPTER", Map.of());
        }

        entries.sort(Comparator.comparing(TimelineEntry::at)
                .thenComparing(TimelineEntry::aggregateId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEntry::eventType, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(entries);
    }

    private static Map<String, Object> money(com.laserpay.pdei.common.money.Money amount) {
        return amount == null ? Map.of()
                : Map.of("amountMinor", amount.amountMinor(), "currency", amount.currency());
    }

    private static void add(List<TimelineEntry> entries, Instant at, String eventType,
                            AggregateType aggregateType, String aggregateId, String summary,
                            String source, Map<String, Object> details) {
        if (at == null) {
            return;
        }
        entries.add(new TimelineEntry(aggregateId + "@" + eventType, at, eventType, aggregateType,
                aggregateId, summary, source, details));
    }
}
