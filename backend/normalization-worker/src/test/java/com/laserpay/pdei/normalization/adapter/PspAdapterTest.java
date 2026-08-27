package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.normalization.RawEvents;
import com.laserpay.pdei.normalization.support.MonetaryPrecisionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PspAdapterTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private final PspAdapter adapter = new PspAdapter("INR");

    @Test
    @DisplayName("maps a wrapped capture webhook, preserving the source instant as occurredAt")
    void mapsPaymentCaptured() {
        RawEventEnvelope raw = RawEvents.of("PSP", "payment_intent.succeeded", """
                {
                  "id": "evt_1",
                  "type": "payment_intent.succeeded",
                  "data": {
                    "object": {
                      "id": "pi_9f2c",
                      "amount": 1299900,
                      "currency": "inr",
                      "captured_at": "2026-08-26T09:00:00Z",
                      "balance_transaction": "txn_552",
                      "metadata": { "transaction_id": "TX-82918" }
                    }
                  }
                }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.PaymentCaptured);
        assertThat(event.aggregateType()).isEqualTo(AggregateType.PAYMENT);
        assertThat(event.aggregateId()).isEqualTo("PAY-pi_9f2c");
        assertThat(event.merchantId()).isEqualTo(RawEvents.MERCHANT_ID);
        assertThat(event.source()).isEqualTo(EventSource.PSP_ADAPTER);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(event.partitionKey()).isEqualTo("MER-0001:PAY-pi_9f2c");

        assertThat(event.payload().path("transactionId").asText()).isEqualTo("TX-82918");
        assertThat(event.payload().path("capturedAmount").path("amountMinor").asLong())
                .isEqualTo(1_299_900L);
        assertThat(event.payload().path("capturedAmount").path("currency").asText()).isEqualTo("INR");
        assertThat(event.payload().path("settlementReference").asText()).isEqualTo("txn_552");
    }

    @Test
    @DisplayName("lateness survives normalization: observedAt is stamped, occurredAt is not touched")
    void preservesLateness() {
        RawEventEnvelope raw = RawEvents.of("stripe", "charge.captured", """
                { "id": "ch_1", "amount": 500, "currency": "INR",
                  "captured_at": "2026-08-20T00:00:00Z" }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(event.ingestionLagMillis()).isPositive();
    }

    @Test
    @DisplayName("maps an authorization, carrying the AVS/CVV/device fields evidence is derived from")
    void mapsPaymentAuthorized() {
        RawEventEnvelope raw = RawEvents.of("PSP", "payment_intent.authorized", """
                { "id": "pi_77", "amount": 100000, "currency": "INR",
                  "authorized_at": 1787644800,
                  "avs_result": "y", "cvv_result": "m",
                  "device_fingerprint": "fp_abc", "client_ip": "10.0.0.9" }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.PaymentAuthorized);
        assertThat(event.payload().path("avsResult").asText()).isEqualTo("Y");
        assertThat(event.payload().path("cvvResult").asText()).isEqualTo("M");
        assertThat(event.payload().path("deviceFingerprint").asText()).isEqualTo("fp_abc");
        assertThat(event.payload().path("ipAddress").asText()).isEqualTo("10.0.0.9");
        // epoch seconds are accepted and converted
        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochSecond(1787644800L));
    }

    @Test
    @DisplayName("maps a refund, defaulting isPartial and prefixing the refund id")
    void mapsRefundProcessed() {
        RawEventEnvelope raw = RawEvents.of("razorpay", "refund.succeeded", """
                { "id": "rfnd_31", "payment_id": "pay_9", "amount": 25000, "currency": "INR",
                  "processed_at": "2026-08-26T08:00:00Z", "is_partial": true,
                  "metadata": { "transaction_id": "TX-82918" } }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.RefundProcessed);
        assertThat(event.aggregateId()).isEqualTo("REF-rfnd_31");
        assertThat(event.payload().path("paymentId").asText()).isEqualTo("PAY-pay_9");
        assertThat(event.payload().path("isPartial").asBoolean()).isTrue();
        assertThat(event.payload().path("amount").path("amountMinor").asLong()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("translates a network reason code into the canonical DisputeReasonCode")
    void mapsDisputeCreated() {
        RawEventEnvelope raw = RawEvents.of("PSP", "charge.dispute.created", """
                { "id": "dp_5", "charge": "ch_1", "amount": 1299900, "currency": "INR",
                  "reason": "product_not_received", "network_reason_code": "13.1",
                  "created": "2026-08-26T07:00:00Z",
                  "evidence_details": { "due_by": "2026-09-10T00:00:00Z" },
                  "metadata": { "transaction_id": "TX-82918" } }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.DisputeCreated);
        assertThat(event.aggregateId()).isEqualTo("DSP-dp_5");
        assertThat(event.payload().path("reasonCode").asText()).isEqualTo("GOODS_NOT_RECEIVED");
        assertThat(event.payload().path("status").asText()).isEqualTo("OPEN");
        assertThat(event.payload().path("deadlineAt").asText())
                .isEqualTo("2026-09-10T00:00:00Z");
        assertThat(event.payload().path("disputedAmount").path("amountMinor").asLong())
                .isEqualTo(1_299_900L);
    }

    @Test
    @DisplayName("an unmapped dispute reason code is dead-lettered rather than guessed")
    void refusesUnknownReasonCode() {
        RawEventEnvelope raw = RawEvents.of("PSP", "dispute.created", """
                { "id": "dp_6", "amount": 100, "currency": "INR", "reason": "some_new_code",
                  "created": "2026-08-26T07:00:00Z" }
                """);

        assertThatThrownBy(() -> adapter.normalize(raw, OBSERVED_AT))
                .isInstanceOf(UnmappableEventException.class)
                .hasMessageContaining("unmapped dispute reason code");
    }

    @Test
    @DisplayName("an unknown source event type is unmappable, never approximated")
    void refusesUnknownSourceEventType() {
        RawEventEnvelope raw = RawEvents.of("PSP", "payout.paid", "{\"id\":\"po_1\"}");

        assertThatThrownBy(() -> adapter.normalize(raw, OBSERVED_AT))
                .isInstanceOf(UnmappableEventException.class)
                .hasMessageContaining("no mapping");
    }

    @Test
    @DisplayName("a floating-point monetary literal is rejected, never rounded")
    void refusesFloatingPointMoney() {
        RawEventEnvelope raw = RawEvents.of("PSP", "payment_intent.succeeded", """
                { "id": "pi_bad", "amount": 12999.00, "currency": "INR",
                  "captured_at": "2026-08-26T09:00:00Z" }
                """);

        assertThatThrownBy(() -> adapter.normalize(raw, OBSERVED_AT))
                .isInstanceOf(MonetaryPrecisionException.class);
    }

    @Test
    @DisplayName("the canonical event id is a pure function of the raw event id and type")
    void derivesDeterministicEventId() {
        String body = """
                { "id": "pi_det", "amount": 100, "currency": "INR",
                  "captured_at": "2026-08-26T09:00:00Z" }
                """;
        RawEventEnvelope first = RawEvents.of("raw-fixed", "PSP", "payment_intent.succeeded", body,
                java.util.Map.of());
        RawEventEnvelope second = RawEvents.of("raw-fixed", "PSP", "payment_intent.succeeded", body,
                java.util.Map.of());

        assertThat(adapter.normalize(first, OBSERVED_AT).eventId())
                .isEqualTo(adapter.normalize(second, Instant.now()).eventId());
    }

    @Test
    @DisplayName("claims its vendor aliases regardless of case and separators")
    void claimsAliases() {
        assertThat(adapter.supports(RawEvents.of("psp_adapter", "payment.captured", "{}"))).isTrue();
        assertThat(adapter.supports(RawEvents.of("Stripe", "payment.captured", "{}"))).isTrue();
        assertThat(adapter.supports(RawEvents.of("LOGISTICS", "payment.captured", "{}"))).isFalse();
    }
}
