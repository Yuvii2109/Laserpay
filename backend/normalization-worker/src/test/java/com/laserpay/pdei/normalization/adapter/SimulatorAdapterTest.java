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

class SimulatorAdapterTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private final SimulatorAdapter adapter = new SimulatorAdapter("INR");

    @Test
    @DisplayName("accepts the canonical vocabulary and passes the payload through unchanged")
    void mapsCanonicalPayload() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("SIMULATOR", "PaymentCaptured", """
                { "payload": { "paymentId": "PAY-1", "transactionId": "TX-1",
                  "capturedAmount": { "amountMinor": 129900, "currency": "INR" },
                  "capturedAt": "2026-08-26T09:00:00Z" } }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.PaymentCaptured);
        assertThat(event.source()).isEqualTo(EventSource.SIMULATOR);
        assertThat(event.aggregateId()).isEqualTo("PAY-1");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(event.payload().path("capturedAmount").path("amountMinor").asLong())
                .isEqualTo(129_900L);
    }

    @Test
    @DisplayName("a backdated occurredAt survives so DELAYED_EVENT chaos is observable downstream")
    void preservesBackdatedOccurredAt() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("SIM", "ShipmentDelivered", """
                { "shipmentId": "SHP-9", "deliveredAt": "2026-08-01T00:00:00Z" }
                """), OBSERVED_AT);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(event.ingestionLagMillis()).isGreaterThan(0);
    }

    @Test
    @DisplayName("internal event families cannot be injected from outside the platform")
    void refusesInternalEventTypes() {
        assertThatThrownBy(() -> adapter.normalize(
                RawEvents.of("SIMULATOR", "CaseOpened", "{ \"caseId\": \"CASE-1\" }"), OBSERVED_AT))
                .isInstanceOf(UnmappableEventException.class);

        assertThatThrownBy(() -> adapter.normalize(
                RawEvents.of("SIMULATOR", "ReadinessRecomputed", "{ \"transactionId\": \"TX-1\" }"),
                OBSERVED_AT))
                .isInstanceOf(UnmappableEventException.class);
    }

    @Test
    @DisplayName("a payload with no aggregate identifier is unmappable, not defaulted")
    void refusesPayloadWithoutAggregateId() {
        assertThatThrownBy(() -> adapter.normalize(
                RawEvents.of("SIMULATOR", "OrderCreated", "{ \"placedAt\": \"2026-08-26T09:00:00Z\" }"),
                OBSERVED_AT))
                .isInstanceOf(UnmappableEventException.class)
                .hasMessageContaining("identifier");
    }
}
