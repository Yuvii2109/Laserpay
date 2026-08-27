package com.laserpay.pdei.common.event;

import com.laserpay.pdei.common.error.UnknownEventTypeException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalEventTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-26T10:15:30.123Z");
    private static final Instant OBSERVED = Instant.parse("2026-08-26T10:15:31.004Z");

    private static CanonicalEvent.Builder paymentCaptured() {
        return CanonicalEvent.builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .eventType(EventType.PaymentCaptured)
                .aggregateId("PAY-000123")
                .merchantId("MER-0001")
                .occurredAt(OCCURRED)
                .observedAt(OBSERVED)
                .source(EventSource.PSP_ADAPTER)
                .idempotencyKey("psp:ch_123:captured")
                .payloadFrom(Map.of("amountMinor", 1_299_900L, "currency", "INR"));
    }

    @Test
    void partitionKeyIsMerchantIdColonAggregateId() {
        assertThat(paymentCaptured().build().partitionKey()).isEqualTo("MER-0001:PAY-000123");
    }

    @Test
    void partitionKeyIsStableAcrossEveryEventForTheSameAggregate() {
        String captured = paymentCaptured().build().partitionKey();
        String authorized = paymentCaptured()
                .eventId("99999999-2222-3333-4444-555555555555")
                .eventType(EventType.PaymentAuthorized)
                .build()
                .partitionKey();

        assertThat(captured).isEqualTo(authorized);
    }

    @Test
    void partitionKeyDiffersPerMerchantEvenForAnIdenticalAggregateId() {
        String first = paymentCaptured().build().partitionKey();
        String second = paymentCaptured().merchantId("MER-0002").build().partitionKey();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aggregateTypeIsDerivedFromTheEventTypeWhenNotSupplied() {
        assertThat(paymentCaptured().build().aggregateType()).isEqualTo(AggregateType.PAYMENT);
        assertThat(CanonicalEvent.builder()
                .eventId("e1").eventType(EventType.EvidenceAdded).aggregateId("EV-1")
                .merchantId("MER-1").occurredAt(OCCURRED).source(EventSource.INTERNAL)
                .build()
                .aggregateType()).isEqualTo(AggregateType.EVIDENCE);
    }

    @Test
    void tolerantDefaultsAreApplied() {
        CanonicalEvent event = CanonicalEvent.builder()
                .eventId("evt-1")
                .eventType(EventType.OrderCreated)
                .aggregateId("ORD-9")
                .merchantId("MER-1")
                .occurredAt(OCCURRED)
                .source(EventSource.ORDER_SYSTEM)
                .schemaVersion(0)
                .build();

        assertThat(event.schemaVersion()).isEqualTo(CanonicalEvent.CURRENT_SCHEMA_VERSION);
        assertThat(event.observedAt()).isEqualTo(OCCURRED);
        assertThat(event.correlationId()).isEqualTo("evt-1");
        assertThat(event.idempotencyKey()).isEqualTo("evt-1");
        assertThat(event.causationId()).isNull();
        assertThat(event.payload().isObject()).isTrue();
        assertThat(event.payload().size()).isZero();
    }

    @Test
    void mandatoryFieldsAreEnforced() {
        assertThatThrownBy(() -> paymentCaptured().eventId(null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> paymentCaptured().merchantId("  ").build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("merchantId");
        assertThatThrownBy(() -> paymentCaptured().occurredAt(null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> paymentCaptured().source(null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("source");
    }

    @Test
    void causedByPropagatesCorrelationAndRecordsCausation() {
        CanonicalEvent parent = paymentCaptured().correlationId("corr-42").build();

        CanonicalEvent child = CanonicalEvent.builder()
                .eventId("evt-child")
                .eventType(EventType.EvidenceAdded)
                .aggregateId("EV-77")
                .occurredAt(OCCURRED)
                .source(EventSource.INTERNAL)
                .causedBy(parent)
                .build();

        assertThat(child.correlationId()).isEqualTo("corr-42");
        assertThat(child.causationId()).isEqualTo(parent.eventId());
        assertThat(child.merchantId()).isEqualTo("MER-0001");
    }

    /** Typed projection of a PaymentCaptured payload. */
    record CapturedPayload(long amountMinor, String currency) {
    }

    /** Deliberately incompatible with the payload used in the failure test. */
    record MismatchedPayload(int mandatoryField) {
    }

    @Test
    void payloadCanBeProjectedOntoARecord() {
        CapturedPayload payload = paymentCaptured().build().payloadAs(CapturedPayload.class);

        assertThat(Money.of(payload.amountMinor(), payload.currency()))
                .isEqualTo(Money.of(1_299_900, "INR"));
    }

    @Test
    void payloadProjectionFailureSurfacesAsValidationException() {
        CanonicalEvent event = paymentCaptured()
                .payloadFrom(Map.of("mandatoryField", "not-a-number"))
                .build();

        assertThatThrownBy(() -> event.payloadAs(MismatchedPayload.class))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void roundTripsThroughJsonWithTheContractFieldNames() {
        CanonicalEvent original = paymentCaptured().build();

        String json = Json.write(original);
        assertThat(json)
                .contains("\"eventType\":\"PaymentCaptured\"")
                .contains("\"aggregateType\":\"PAYMENT\"")
                .contains("\"source\":\"PSP_ADAPTER\"")
                .contains("\"occurredAt\":\"2026-08-26T10:15:30.123Z\"")
                .contains("\"observedAt\":\"2026-08-26T10:15:31.004Z\"");

        CanonicalEvent parsed = Json.read(json, CanonicalEvent.class);

        assertThat(parsed.eventId()).isEqualTo(original.eventId());
        assertThat(parsed.eventType()).isEqualTo(original.eventType());
        assertThat(parsed.aggregateType()).isEqualTo(original.aggregateType());
        assertThat(parsed.merchantId()).isEqualTo(original.merchantId());
        assertThat(parsed.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(parsed.observedAt()).isEqualTo(original.observedAt());
        assertThat(parsed.source()).isEqualTo(original.source());
        assertThat(parsed.idempotencyKey()).isEqualTo(original.idempotencyKey());
        assertThat(parsed.partitionKey()).isEqualTo(original.partitionKey());
        // Compared canonically: a JSON round trip narrows 1299900L to an int node, so node-level
        // equality is not a meaningful assertion. Canonical text is what hashing relies on anyway.
        assertThat(Json.canonical(parsed.payload())).isEqualTo(Json.canonical(original.payload()));
    }

    @Test
    void unknownEventTypesAreRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> EventType.fromWire("PaymentSettled"))
                .isInstanceOf(UnknownEventTypeException.class)
                .hasMessageContaining("PaymentSettled");
        assertThatThrownBy(() -> EventType.fromWire("PAYMENTCAPTURED"))
                .isInstanceOf(UnknownEventTypeException.class);
        assertThat(EventType.fromWire("PaymentCaptured")).isEqualTo(EventType.PaymentCaptured);
    }

    @Test
    void ingestionLagIsNeverNegative() {
        assertThat(paymentCaptured().build().ingestionLagMillis()).isEqualTo(881L);
        // An out-of-order source clock must not produce a negative lag metric.
        assertThat(paymentCaptured().observedAt(OCCURRED.minusSeconds(5)).build()
                .ingestionLagMillis()).isZero();
    }

    @Test
    void toBuilderCopiesEveryField() {
        CanonicalEvent original = paymentCaptured().causationId("cause-1").build();
        assertThat(original.toBuilder().build()).isEqualTo(original);
    }
}
