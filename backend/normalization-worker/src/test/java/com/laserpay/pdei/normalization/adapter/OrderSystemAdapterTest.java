package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.normalization.RawEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSystemAdapterTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T10:15:31.004Z");

    private final OrderSystemAdapter adapter = new OrderSystemAdapter("INR");

    @Test
    @DisplayName("normalizes order lines into canonical minor-unit money")
    void mapsOrderCreated() {
        RawEventEnvelope raw = RawEvents.of("shopify", "orders/create", """
                {
                  "order": {
                    "id": "1099",
                    "transaction_id": "TX-82918",
                    "customer_id": "77",
                    "currency": "inr",
                    "total_price": 1299900,
                    "created_at": "2026-08-25T12:00:00Z",
                    "line_items": [
                      { "sku": "SKU-1", "title": "Wireless headphones", "quantity": 2,
                        "unit_price": 500000 },
                      { "sku": "SKU-2", "title": "Cable", "quantity": 1, "line_total": 299900 }
                    ],
                    "shipping_address": { "city": "Bengaluru" }
                  }
                }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.eventType()).isEqualTo(EventType.OrderCreated);
        assertThat(event.source()).isEqualTo(EventSource.ORDER_SYSTEM);
        assertThat(event.aggregateId()).isEqualTo("ORD-1099");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-25T12:00:00Z"));
        assertThat(event.payload().path("transactionId").asText()).isEqualTo("TX-82918");
        assertThat(event.payload().path("customerId").asText()).isEqualTo("CUS-77");
        assertThat(event.payload().path("orderTotal").path("amountMinor").asLong())
                .isEqualTo(1_299_900L);

        var lines = event.payload().path("lines");
        assertThat(lines.size()).isEqualTo(2);
        // lineTotal derived from unitPrice by integer multiplication
        assertThat(lines.get(0).path("lineTotal").path("amountMinor").asLong()).isEqualTo(1_000_000L);
        assertThat(lines.get(0).path("lineTotal").path("currency").asText()).isEqualTo("INR");
        // unitPrice derived from lineTotal when the quantity divides it exactly
        assertThat(lines.get(1).path("unitPrice").path("amountMinor").asLong()).isEqualTo(299_900L);
        assertThat(event.payload().path("shippingAddress").path("city").asText())
                .isEqualTo("Bengaluru");
    }

    @Test
    @DisplayName("a decimal-string total is converted by integer digit shifting")
    void convertsDecimalStringTotals() {
        RawEventEnvelope raw = RawEvents.of("ORDER_SYSTEM", "order.created", """
                { "id": "ORD-55", "currency": "INR", "total": "1299.00",
                  "placed_at": "2026-08-25T12:00:00Z", "lines": [] }
                """);

        CanonicalEvent event = adapter.normalize(raw, OBSERVED_AT);

        assertThat(event.payload().path("orderTotal").path("amountMinor").asLong()).isEqualTo(129_900L);
    }

    @Test
    @DisplayName("maps fulfilment and cancellation with their own instants")
    void mapsFulfilmentAndCancellation() {
        CanonicalEvent fulfilled = adapter.normalize(RawEvents.of("shopify", "orders/fulfilled", """
                { "order": { "id": "ORD-1", "fulfilled_at": "2026-08-26T06:00:00Z",
                  "line_items": [ { "sku": "SKU-1", "quantity": 2 } ] } }
                """), OBSERVED_AT);

        assertThat(fulfilled.eventType()).isEqualTo(EventType.OrderFulfilled);
        assertThat(fulfilled.occurredAt()).isEqualTo(Instant.parse("2026-08-26T06:00:00Z"));
        assertThat(fulfilled.payload().path("fulfilledLines").size()).isEqualTo(1);

        CanonicalEvent cancelled = adapter.normalize(RawEvents.of("shopify", "orders/cancelled", """
                { "order": { "id": "ORD-1", "cancelled_at": "2026-08-26T07:00:00Z",
                  "cancel_reason": "customer request", "cancelled_by": "customer" } }
                """), OBSERVED_AT);

        assertThat(cancelled.eventType()).isEqualTo(EventType.OrderCancelled);
        assertThat(cancelled.payload().path("cancelledBy").asText()).isEqualTo("CUSTOMER");
        assertThat(cancelled.payload().path("reason").asText()).isEqualTo("customer request");
    }
}
