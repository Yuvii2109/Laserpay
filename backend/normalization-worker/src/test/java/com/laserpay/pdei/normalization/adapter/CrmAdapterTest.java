package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.normalization.RawEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CrmAdapterTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private final CrmAdapter adapter = new CrmAdapter("INR");

    @Test
    @DisplayName("an outbound reply becomes CommunicationCreated with direction OUTBOUND")
    void mapsOutboundCommunication() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("zendesk", "ticket.reply.outbound", """
                { "message": { "id": "msg_1", "via": "email",
                  "custom_fields": { "transaction_id": "TX-82918" },
                  "requester_id": "77", "subject": "Your order has shipped",
                  "body": "Hi,\\n\\n  your order shipped today.",
                  "from_email": "support@merchant.example", "to_email": "customer@example.com",
                  "sent_at": "2026-08-25T19:00:00Z" } }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.CommunicationCreated);
        assertThat(event.source()).isEqualTo(EventSource.CRM);
        assertThat(event.aggregateId()).isEqualTo("COM-msg_1");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-25T19:00:00Z"));
        assertThat(event.payload().path("direction").asText()).isEqualTo("OUTBOUND");
        assertThat(event.payload().path("channel").asText()).isEqualTo("EMAIL");
        assertThat(event.payload().path("transactionId").asText()).isEqualTo("TX-82918");
        assertThat(event.payload().path("customerId").asText()).isEqualTo("CUS-77");
        // whitespace is collapsed so the preview is one readable line
        assertThat(event.payload().path("bodyPreview").asText())
                .isEqualTo("Hi, your order shipped today.");
    }

    @Test
    @DisplayName("an inbound message becomes CommunicationReceived with direction INBOUND")
    void mapsInboundCommunication() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("CRM", "email.received", """
                { "id": "msg_2", "channel": "WhatsApp", "occurred_at": "2026-08-26T08:00:00Z",
                  "body": "Where is my order?" }
                """), OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.CommunicationReceived);
        assertThat(event.payload().path("direction").asText()).isEqualTo("INBOUND");
        assertThat(event.payload().path("channel").asText()).isEqualTo("WHATSAPP");
    }

    @Test
    @DisplayName("an unrecognised channel degrades to PORTAL rather than failing the event")
    void degradesUnknownChannel() {
        CanonicalEvent event = adapter.normalize(RawEvents.of("CRM", "message.inbound", """
                { "id": "msg_3", "channel": "carrier-pigeon", "occurred_at": "2026-08-26T08:00:00Z" }
                """), OBSERVED_AT);

        assertThat(event.payload().path("channel").asText()).isEqualTo("PORTAL");
    }

    @Test
    @DisplayName("a long body is truncated: Kafka is not a document store")
    void truncatesLongBodies() {
        String longBody = "x".repeat(2000);
        CanonicalEvent event = adapter.normalize(RawEvents.of("CRM", "email.sent",
                "{ \"id\": \"msg_4\", \"body\": \"" + longBody + "\" }"), OBSERVED_AT);

        assertThat(event.payload().path("bodyPreview").asText()).hasSize(512 + 3);
    }
}
