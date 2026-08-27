package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.normalization.RawEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantPortalAdapterTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private final MerchantPortalAdapter adapter = new MerchantPortalAdapter("INR");

    @Test
    @DisplayName("a merchant-attested delivery is stamped MERCHANT_PORTAL provenance")
    void mapsManualDeliveryConfirmation() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("MERCHANT_PORTAL", "delivery.confirmed", """
                { "payload": { "shipmentId": "SHP-771", "transactionId": "TX-82918",
                  "signedBy": "R. Sharma", "occurredAt": "2026-08-27T11:00:00Z",
                  "enteredBy": "ops@merchant.example" } }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.ShipmentDelivered);
        assertThat(event.source()).isEqualTo(EventSource.MERCHANT_PORTAL);
        assertThat(event.aggregateId()).isEqualTo("SHP-771");
        assertThat(event.payload().path("proofType").asText()).isEqualTo("MERCHANT_ATTESTED");
        assertThat(event.payload().path("enteredBy").asText()).isEqualTo("ops@merchant.example");
        assertThat(event.payload().path("deliveredAt").asText()).isEqualTo("2026-08-27T11:00:00Z");
    }

    @Test
    @DisplayName("a logged phone call becomes an outbound communication")
    void mapsLoggedCommunication() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("PORTAL", "communication.logged", """
                { "communicationId": "COM-manual-1", "transactionId": "TX-82918",
                  "channel": "phone", "subject": "Called customer",
                  "occurredAt": "2026-08-26T09:30:00Z" }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.CommunicationCreated);
        assertThat(event.payload().path("direction").asText()).isEqualTo("OUTBOUND");
        assertThat(event.payload().path("channel").asText()).isEqualTo("PHONE");
        assertThat(event.aggregateId()).isEqualTo("COM-manual-1");
    }

    @Test
    @DisplayName("a manually reported dispute still requires a recognisable reason code")
    void mapsReportedDispute() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("MERCHANT_PORTAL", "dispute.reported", """
                { "disputeId": "DSP-manual-1", "transactionId": "TX-82918",
                  "reasonCode": "GOODS_NOT_RECEIVED", "amount": 129900,
                  "occurredAt": "2026-08-26T09:30:00Z" }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.DisputeCreated);
        assertThat(event.payload().path("reasonCode").asText()).isEqualTo("GOODS_NOT_RECEIVED");
        assertThat(event.payload().path("disputedAmount").path("amountMinor").asLong())
                .isEqualTo(129_900L);

        assertThatThrownBy(() -> adapter.normalize(RawEvents.of("MERCHANT_PORTAL", "dispute.reported",
                """
                { "disputeId": "DSP-manual-2", "reasonCode": "because", "amount": 1,
                  "occurredAt": "2026-08-26T09:30:00Z" }
                """), OBSERVED_AT))
                .isInstanceOf(UnmappableEventException.class);
    }
}
