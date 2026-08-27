package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.normalization.RawEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogisticsAdapterTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private final LogisticsAdapter adapter = new LogisticsAdapter("INR");

    @Test
    @DisplayName("maps a dispatch, preserving carrier, tracking and the estimated delivery date")
    void mapsShipmentDispatched() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("delhivery", "shipment.picked_up", """
                { "shipment_id": "SHP-771", "order_id": "ORD-1099", "awb": "AWB99",
                  "courier": "Delhivery", "picked_up_at": "2026-08-25T18:30:00Z",
                  "edd": "2026-08-28T00:00:00Z" }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.ShipmentDispatched);
        assertThat(event.source()).isEqualTo(EventSource.LOGISTICS);
        assertThat(event.aggregateId()).isEqualTo("SHP-771");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-25T18:30:00Z"));
        assertThat(event.payload().path("carrier").asText()).isEqualTo("Delhivery");
        assertThat(event.payload().path("trackingNumber").asText()).isEqualTo("AWB99");
        assertThat(event.payload().path("estimatedDeliveryAt").asText())
                .isEqualTo("2026-08-28T00:00:00Z");
    }

    @Test
    @DisplayName("maps a delivery, converting decimal geo into integer micro-degrees")
    void mapsShipmentDelivered() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("LOGISTICS", "shipment.delivered", """
                { "shipment_id": "SHP-771", "delivered_at": "2026-08-27T11:02:00Z",
                  "signed_by": "R. Sharma", "pod_type": "signature",
                  "proof_object_key": "pod/SHP-771.png",
                  "geo": { "lat": "12.9716", "lon": "77.5946" },
                  "delivery_address": { "city": "Bengaluru" } }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.ShipmentDelivered);
        assertThat(event.payload().path("deliveryId").asText()).isEqualTo("DLV-771");
        assertThat(event.payload().path("signedBy").asText()).isEqualTo("R. Sharma");
        assertThat(event.payload().path("proofType").asText()).isEqualTo("SIGNATURE");
        assertThat(event.payload().path("geo").path("latMicro").asInt()).isEqualTo(12_971_600);
        assertThat(event.payload().path("geo").path("lonMicro").asInt()).isEqualTo(77_594_600);
        assertThat(event.payload().path("attempts").asInt()).isEqualTo(1);
        assertThat(event.payload().path("deliveryAddress").path("city").asText())
                .isEqualTo("Bengaluru");
    }

    @Test
    @DisplayName("the derived delivery id is stable across replays of the same event")
    void derivesStableDeliveryId() {
        String body = """
                { "shipment_id": "SHP-900", "delivered_at": "2026-08-27T11:02:00Z" }
                """;
        String first = adapter.normalize(RawEvents.of("LOGISTICS", "shipment.delivered", body),
                OBSERVED_AT).payload().path("deliveryId").asText();
        String second = adapter.normalize(RawEvents.of("LOGISTICS", "shipment.delivered", body),
                Instant.now()).payload().path("deliveryId").asText();

        assertThat(first).isEqualTo("DLV-900").isEqualTo(second);
    }

    @Test
    @DisplayName("a shipment booking carries the declared value as minor-unit money")
    void mapsShipmentCreated() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("bluedart", "shipment.booked", """
                { "id": "SHP-5", "order_id": "ORD-5", "declared_value": 129900,
                  "currency": "INR", "booked_at": "2026-08-25T09:00:00Z",
                  "service_level": "EXPRESS" }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.ShipmentCreated);
        assertThat(event.payload().path("declaredValue").path("amountMinor").asLong())
                .isEqualTo(129_900L);
        assertThat(event.payload().path("declaredValue").path("currency").asText()).isEqualTo("INR");
        assertThat(event.payload().path("serviceLevel").asText()).isEqualTo("EXPRESS");
    }
}
