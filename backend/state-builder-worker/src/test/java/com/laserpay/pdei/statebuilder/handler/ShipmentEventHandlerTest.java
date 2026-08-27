package com.laserpay.pdei.statebuilder.handler;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.persistence.entity.CustomerEntity;
import com.laserpay.pdei.persistence.entity.DeliveryEntity;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.statebuilder.EvidenceStubs;
import com.laserpay.pdei.statebuilder.Events;
import com.laserpay.pdei.statebuilder.Repositories;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentEventHandlerTest {

    private static final String SHIPMENT_ID = "SHP-000771";
    private static final String ORDER_ID = "ORD-001099";
    private static final Instant DISPATCHED_AT = Instant.parse("2026-08-25T18:30:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-08-27T11:02:00Z");

    private final Repositories.Store<ShipmentEntity> shipmentStore = new Repositories.Store<>();
    private final Repositories.Store<DeliveryEntity> deliveryStore = new Repositories.Store<>();
    private final Repositories.Store<OrderEntity> orderStore = new Repositories.Store<>();
    private final Repositories.Store<MerchantEntity> merchantStore = new Repositories.Store<>();
    private final Repositories.Store<CustomerEntity> customerStore = new Repositories.Store<>();
    private final Repositories.Store<PaymentEntity> paymentStore = new Repositories.Store<>();

    private EvidenceStubs.Recorder evidence;
    private ShipmentEventHandler handler;

    @BeforeEach
    void setUp() {
        evidence = EvidenceStubs.recorder();
        ReferenceData referenceData = new ReferenceData(
                Repositories.merchants(merchantStore),
                Repositories.customers(customerStore),
                Repositories.orders(orderStore),
                Repositories.shipments(shipmentStore),
                Repositories.payments(paymentStore),
                "INR");
        handler = new ShipmentEventHandler(
                Repositories.shipments(shipmentStore),
                Repositories.deliveries(deliveryStore),
                referenceData,
                new DerivedEvidenceService(evidence.service()),
                "INR");
    }

    @Test
    @DisplayName("a dispatch projects the shipment and derives SHIPPING_RECORD")
    void projectsDispatch() {
        handler.handle(dispatched(DISPATCHED_AT));

        ShipmentEntity shipment = shipmentStore.require(SHIPMENT_ID);
        assertThat(shipment.getStatus()).isEqualTo("DISPATCHED");
        assertThat(shipment.getCarrier()).isEqualTo("Delhivery");
        assertThat(shipment.getTrackingNumber()).isEqualTo("AWB99");
        assertThat(shipment.getShippedAt()).isEqualTo(DISPATCHED_AT);
        assertThat(shipment.getTransactionId()).isEqualTo(Events.TRANSACTION_ID);

        assertThat(evidence.types()).containsExactly(EvidenceType.SHIPPING_RECORD);
    }

    @Test
    @DisplayName("a delivery projects the deliveries row, geo as micro-degrees, and DELIVERY_PROOF")
    void projectsDelivery() {
        handler.handle(delivered(DELIVERED_AT));

        assertThat(shipmentStore.require(SHIPMENT_ID).getStatus()).isEqualTo("DELIVERED");

        DeliveryEntity delivery = deliveryStore.require("DLV-000771");
        assertThat(delivery.getShipmentId()).isEqualTo(SHIPMENT_ID);
        assertThat(delivery.getStatus()).isEqualTo("DELIVERED");
        assertThat(delivery.getDeliveredAt()).isEqualTo(DELIVERED_AT);
        assertThat(delivery.getSignedBy()).isEqualTo("R. Sharma");
        assertThat(delivery.isSignatureCaptured()).isTrue();
        assertThat(delivery.getGeoLatMicro()).isEqualTo(12_971_600);
        assertThat(delivery.getGeoLonMicro()).isEqualTo(77_594_600);

        assertThat(evidence.types()).containsExactly(EvidenceType.DELIVERY_PROOF);
        assertThat(evidence.only().summary()).contains("delivered").contains("R. Sharma");
    }

    @Test
    @DisplayName("a shipment referencing an unknown order creates a visible stub rather than failing")
    void createsStubOrderForTheForeignKey() {
        handler.handle(dispatched(DISPATCHED_AT));

        OrderEntity order = orderStore.require(ORDER_ID);
        assertThat(ProjectionWatermark.isStub(order.getMetadata())).isTrue();
        assertThat(order.getStatus()).isEqualTo("CREATED");
        assertThat(order.getAmount().getAmountMinor()).isZero();
        // A stub carries no watermark, so the real OrderCreated still applies when it lands.
        assertThat(ProjectionWatermark.lastOccurredAt(order.getMetadata())).isNull();
    }

    @Test
    @DisplayName("duplicate delivery: the second application of the same event changes nothing")
    void duplicateDeliveryIsANoOp() {
        CanonicalEvent event = delivered(DELIVERED_AT);

        handler.handle(event);
        handler.handle(event);

        assertThat(deliveryStore.size()).isEqualTo(1);
        assertThat(deliveryStore.require("DLV-000771").getAttempts()).isEqualTo(1);
        assertThat(evidence.types()).containsExactly(EvidenceType.DELIVERY_PROOF);
    }

    @Test
    @DisplayName("out-of-order rejection: a dispatch arriving after the delivery is ignored")
    void rejectsDispatchAfterDelivery() {
        handler.handle(delivered(DELIVERED_AT));
        assertThat(shipmentStore.require(SHIPMENT_ID).getStatus()).isEqualTo("DELIVERED");

        handler.handle(dispatched(DISPATCHED_AT));

        ShipmentEntity shipment = shipmentStore.require(SHIPMENT_ID);
        assertThat(shipment.getStatus()).isEqualTo("DELIVERED");
        assertThat(shipment.getShippedAt()).isNull();
        assertThat(evidence.types()).containsExactly(EvidenceType.DELIVERY_PROOF);
    }

    // --- fixtures -------------------------------------------------------------------------------

    private static CanonicalEvent dispatched(Instant at) {
        return Events.of(EventType.ShipmentDispatched, SHIPMENT_ID, at, """
                { "shipmentId": "SHP-000771", "orderId": "ORD-001099",
                  "transactionId": "TX-82918", "carrier": "Delhivery", "trackingNumber": "AWB99",
                  "originHub": "BLR-HUB", "dispatchedAt": "2026-08-25T18:30:00Z",
                  "estimatedDeliveryAt": "2026-08-28T00:00:00Z" }
                """);
    }

    private static CanonicalEvent delivered(Instant at) {
        return Events.of(EventType.ShipmentDelivered, SHIPMENT_ID, at, """
                { "shipmentId": "SHP-000771", "transactionId": "TX-82918",
                  "deliveryId": "DLV-000771", "deliveredAt": "2026-08-27T11:02:00Z",
                  "signedBy": "R. Sharma", "proofType": "SIGNATURE",
                  "proofObjectKey": "pod/SHP-000771.png",
                  "geo": { "latMicro": 12971600, "lonMicro": 77594600 },
                  "deliveryAddress": { "city": "Bengaluru" } }
                """);
    }
}
