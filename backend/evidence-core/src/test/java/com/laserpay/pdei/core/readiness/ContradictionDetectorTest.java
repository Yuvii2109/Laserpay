package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.TestFixtures;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.TransactionFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionDetectorTest {

    private static final Instant NOW = TestFixtures.NOW;
    private static final String ADDRESS = "12 Main Street, Bengaluru 560001";

    private final ContradictionDetector detector = new ContradictionDetector();

    private static TransactionFacts.OrderFact order(String address, int quantity, Money total) {
        return new TransactionFacts.OrderFact("ORD-1", "FULFILLED", total, address,
                NOW.minusSeconds(86400 * 5), NOW.minusSeconds(86400 * 4),
                List.of(new TransactionFacts.OrderLineFact("OL-1", "SKU-1", "widget", quantity,
                        Money.of(50_00L, "INR"))));
    }

    private static TransactionFacts facts(List<TransactionFacts.PaymentFact> payments,
                                          List<TransactionFacts.OrderFact> orders,
                                          List<TransactionFacts.ShipmentFact> shipments,
                                          List<TransactionFacts.DeliveryFact> deliveries,
                                          List<TransactionFacts.RefundFact> refunds) {
        return new TransactionFacts(TestFixtures.TRANSACTION, TestFixtures.MERCHANT, "CUS-1",
                Money.of(100_00L, "INR"), "CAPTURED", NOW.minusSeconds(86400 * 5), payments, orders,
                shipments, deliveries, refunds, List.of());
    }

    @Test
    @DisplayName("delivery recorded before the shipment was dispatched is a HIGH contradiction")
    void deliveryBeforeDispatch() {
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "DISPATCHED", ADDRESS, 2, NOW.minusSeconds(86400 * 3),
                NOW.minusSeconds(86400));
        TransactionFacts.DeliveryFact delivery = new TransactionFacts.DeliveryFact("DLV-1", "SHP-1",
                "DELIVERED", "R. Kumar", ADDRESS, "SIGNATURE", NOW.minusSeconds(86400 * 2));

        List<ContradictionView> found = detector.detect(
                facts(List.of(), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))), List.of(shipment),
                        List.of(delivery), List.of()),
                List.of(), NOW);

        assertThat(found).filteredOn(c -> ContradictionDetector.FIELD_DELIVERED_AT.equals(c.field()))
                .singleElement()
                .extracting(ContradictionView::severity).isEqualTo(GapSeverity.HIGH);
    }

    @Test
    @DisplayName("a delivery for a shipment that was never dispatched is a HIGH contradiction")
    void deliveryWithoutDispatch() {
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "CREATED", ADDRESS, 2, NOW.minusSeconds(86400 * 3), null);
        TransactionFacts.DeliveryFact delivery = new TransactionFacts.DeliveryFact("DLV-1", "SHP-1",
                "DELIVERED", "R. Kumar", ADDRESS, "SIGNATURE", NOW.minusSeconds(86400));

        List<ContradictionView> found = detector.detect(
                facts(List.of(), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))), List.of(shipment),
                        List.of(delivery), List.of()),
                List.of(), NOW);

        assertThat(found).extracting(ContradictionView::field)
                .contains(ContradictionDetector.FIELD_DISPATCHED_AT);
    }

    @Test
    @DisplayName("a refund larger than the payment it refunds is CRITICAL")
    void refundExceedsCapture() {
        TransactionFacts.PaymentFact payment = new TransactionFacts.PaymentFact("PAY-1", "CAPTURED",
                Money.of(100_00L, "INR"), "psp-ref", NOW.minusSeconds(86400 * 5), null,
                NOW.minusSeconds(86400 * 5), "Y", "M");
        TransactionFacts.RefundFact refund = new TransactionFacts.RefundFact("REF-1", "PAY-1", "PROCESSED",
                Money.of(150_00L, "INR"), NOW.minusSeconds(3600), NOW.minusSeconds(1800));

        List<ContradictionView> found = detector.detect(
                facts(List.of(payment), List.of(), List.of(), List.of(), List.of(refund)), List.of(), NOW);

        assertThat(found).filteredOn(c -> ContradictionDetector.FIELD_REFUND_AMOUNT.equals(c.field()))
                .isNotEmpty()
                .allMatch(c -> c.severity() == GapSeverity.CRITICAL);
    }

    @Test
    @DisplayName("a refund in another currency is CRITICAL and does not attempt an amount comparison")
    void refundCurrencyMismatch() {
        TransactionFacts.PaymentFact payment = new TransactionFacts.PaymentFact("PAY-1", "CAPTURED",
                Money.of(100_00L, "INR"), "psp-ref", NOW.minusSeconds(86400), null,
                NOW.minusSeconds(86400), "Y", "M");
        TransactionFacts.RefundFact refund = new TransactionFacts.RefundFact("REF-1", "PAY-1", "PROCESSED",
                Money.of(10_00L, "USD"), NOW.minusSeconds(3600), NOW.minusSeconds(1800));

        List<ContradictionView> found = detector.detect(
                facts(List.of(payment), List.of(), List.of(), List.of(), List.of(refund)), List.of(), NOW);

        assertThat(found).extracting(ContradictionView::field)
                .contains(ContradictionDetector.FIELD_CURRENCY);
    }

    @Test
    @DisplayName("a delivery signed for at a different address than the order is a HIGH contradiction")
    void deliveryAddressMismatch() {
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "DISPATCHED", ADDRESS, 2, NOW.minusSeconds(86400 * 3),
                NOW.minusSeconds(86400 * 3));
        TransactionFacts.DeliveryFact delivery = new TransactionFacts.DeliveryFact("DLV-1", "SHP-1",
                "DELIVERED", "R. Kumar", "99 Other Road, Chennai 600001", "SIGNATURE",
                NOW.minusSeconds(86400));

        List<ContradictionView> found = detector.detect(
                facts(List.of(), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))), List.of(shipment),
                        List.of(delivery), List.of()),
                List.of(), NOW);

        assertThat(found).filteredOn(c -> ContradictionDetector.FIELD_DELIVERY_ADDRESS.equals(c.field()))
                .anyMatch(c -> c.severity() == GapSeverity.HIGH);
    }

    @Test
    @DisplayName("address normalisation prevents a false mismatch on formatting alone")
    void addressNormalisationAvoidsFalsePositives() {
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "DISPATCHED", "12  main   street, bengaluru 560001", 2,
                NOW.minusSeconds(86400 * 3), NOW.minusSeconds(86400 * 3));
        TransactionFacts.DeliveryFact delivery = new TransactionFacts.DeliveryFact("DLV-1", "SHP-1",
                "DELIVERED", "R. Kumar", "12 Main St., Bengaluru - 560001", "SIGNATURE",
                NOW.minusSeconds(86400));

        List<ContradictionView> found = detector.detect(
                facts(List.of(), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))), List.of(shipment),
                        List.of(delivery), List.of()),
                List.of(), NOW);

        assertThat(found).extracting(ContradictionView::field)
                .doesNotContain(ContradictionDetector.FIELD_DELIVERY_ADDRESS);
    }

    @Test
    @DisplayName("shipping fewer units than were ordered is a MEDIUM contradiction")
    void quantityMismatch() {
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "DISPATCHED", ADDRESS, 1, NOW.minusSeconds(86400 * 3),
                NOW.minusSeconds(86400 * 3));

        List<ContradictionView> found = detector.detect(
                facts(List.of(), List.of(order(ADDRESS, 3, Money.of(150_00L, "INR"))), List.of(shipment),
                        List.of(), List.of()),
                List.of(), NOW);

        assertThat(found).filteredOn(c -> ContradictionDetector.FIELD_QUANTITY.equals(c.field()))
                .singleElement()
                .extracting(ContradictionView::severity).isEqualTo(GapSeverity.MEDIUM);
    }

    @Test
    @DisplayName("a captured amount different from the order total is a MEDIUM contradiction")
    void capturedAmountMismatch() {
        TransactionFacts.PaymentFact payment = new TransactionFacts.PaymentFact("PAY-1", "CAPTURED",
                Money.of(90_00L, "INR"), "psp-ref", NOW.minusSeconds(86400 * 5), null,
                NOW.minusSeconds(86400 * 5), "Y", "M");

        List<ContradictionView> found = detector.detect(
                facts(List.of(payment), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))), List.of(),
                        List.of(), List.of()),
                List.of(), NOW);

        assertThat(found).extracting(ContradictionView::field)
                .contains(ContradictionDetector.FIELD_AMOUNT_MINOR);
    }

    @Test
    @DisplayName("a consistent transaction produces no contradictions")
    void consistentTransactionIsClean() {
        TransactionFacts.PaymentFact payment = new TransactionFacts.PaymentFact("PAY-1", "CAPTURED",
                Money.of(100_00L, "INR"), "psp-ref", NOW.minusSeconds(86400 * 5), null,
                NOW.minusSeconds(86400 * 5), "Y", "M");
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "DISPATCHED", ADDRESS, 2, NOW.minusSeconds(86400 * 3),
                NOW.minusSeconds(86400 * 3));
        TransactionFacts.DeliveryFact delivery = new TransactionFacts.DeliveryFact("DLV-1", "SHP-1",
                "DELIVERED", "R. Kumar", ADDRESS, "SIGNATURE", NOW.minusSeconds(86400));

        List<ContradictionView> found = detector.detect(
                facts(List.of(payment), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))),
                        List.of(shipment), List.of(delivery), List.of()),
                List.of(), NOW);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("contradiction sides name the evidence that documents the record when there is one")
    void contradictionsReferenceEvidenceWhenAvailable() {
        TransactionFacts.ShipmentFact shipment = new TransactionFacts.ShipmentFact("SHP-1", "ORD-1",
                "BlueDart", "TRK-1", "CREATED", ADDRESS, 2, NOW.minusSeconds(86400 * 3), null);
        TransactionFacts.DeliveryFact delivery = new TransactionFacts.DeliveryFact("DLV-1", "SHP-1",
                "DELIVERED", "R. Kumar", ADDRESS, "SIGNATURE", NOW.minusSeconds(86400));

        List<ContradictionView> found = detector.detect(
                facts(List.of(), List.of(order(ADDRESS, 2, Money.of(100_00L, "INR"))), List.of(shipment),
                        List.of(delivery), List.of()),
                List.of(TestFixtures.evidence("EV-DLV", EvidenceType.DELIVERY_PROOF)
                        .relatedEntityId("DLV-1").build()),
                NOW);

        assertThat(found).filteredOn(c -> ContradictionDetector.FIELD_DISPATCHED_AT.equals(c.field()))
                .singleElement()
                .extracting(ContradictionView::left).isEqualTo("EV-DLV");
    }
}
