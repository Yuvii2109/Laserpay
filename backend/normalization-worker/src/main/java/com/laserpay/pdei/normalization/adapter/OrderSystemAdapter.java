package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.normalization.support.Payloads;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commerce platform / ERP order events.
 *
 * <p>Models the webhook vocabulary commerce platforms use ({@code orders/create},
 * {@code orders/fulfilled}) as well as the dotted form ERPs prefer. Order events are where line
 * items enter the platform, and line items are what {@code PRODUCT_NOT_AS_DESCRIBED} and partial
 * fulfilment arguments are built from, so lines are normalized rather than passed through verbatim:
 * every line carries {@code unitPrice} and {@code lineTotal} as proper minor-unit money.
 */
public class OrderSystemAdapter extends AbstractSourceAdapter {

    private static final Map<String, EventType> MAPPINGS = Map.ofEntries(
            Map.entry("orders/create", EventType.OrderCreated),
            Map.entry("order.created", EventType.OrderCreated),
            Map.entry("order.placed", EventType.OrderCreated),
            Map.entry("orders/paid", EventType.OrderCreated),
            Map.entry("orders/fulfilled", EventType.OrderFulfilled),
            Map.entry("order.fulfilled", EventType.OrderFulfilled),
            Map.entry("order.completed", EventType.OrderFulfilled),
            Map.entry("orders/cancelled", EventType.OrderCancelled),
            Map.entry("order.cancelled", EventType.OrderCancelled),
            Map.entry("order.canceled", EventType.OrderCancelled));

    private static final Set<String> ALIASES = Set.of(
            "ORDER_SYSTEM", "ORDERS", "OMS", "ERP", "shopify", "woocommerce", "magento", "unicommerce");

    public OrderSystemAdapter(String defaultCurrency) {
        super("ORDER_SYSTEM", ALIASES, MAPPINGS, defaultCurrency);
    }

    @Override
    public EventSource eventSource() {
        return EventSource.ORDER_SYSTEM;
    }

    @Override
    protected String transactionIdHint(RawEventEnvelope raw) {
        return prefixedFrom(raw.body(), IdPrefix.TRANSACTION, "transactionId", "transaction_id",
                "order.transaction_id", "payment.transaction_id", "note_attributes.transaction_id");
    }

    @Override
    protected CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt) {
        JsonNode wrapped = Payloads.first(raw.body(), "order", "data.order", "data", "payload");
        JsonNode source = wrapped == null ? raw.body() : wrapped;

        String orderId = prefixedFrom(source, IdPrefix.ORDER, "orderId", "order_id", "id", "name",
                "order_number");
        String transactionId = prefixedFrom(source, IdPrefix.TRANSACTION, "transactionId",
                "transaction_id", "payment.transaction_id");
        String currency = Payloads.normalizeCurrency(
                Payloads.text(source, "currency", "currency_code", "presentment_currency"),
                defaultCurrency());

        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "orderId", orderId);
        Payloads.putText(payload, "transactionId", transactionId);

        Instant occurredAt;
        switch (eventType) {
            case OrderCreated -> {
                occurredAt = Payloads.instantOr(source, raw.receivedAt(), "placedAt", "placed_at",
                        "created_at", "createdAt", "processed_at", "order_date");
                Payloads.putText(payload, "customerId", prefixedFrom(source, IdPrefix.CUSTOMER,
                        "customerId", "customer_id", "customer.id"));
                payload.set("lines", lines(source, currency));
                Payloads.putMoney(payload, "orderTotal", Payloads.money(source, currency,
                        "orderTotal", "total_price", "total", "grand_total", "amount"));
                Payloads.putMoney(payload, "taxTotal", Payloads.money(source, currency,
                        "taxTotal", "total_tax", "tax"));
                Payloads.putMoney(payload, "shippingTotal", Payloads.money(source, currency,
                        "shippingTotal", "total_shipping", "shipping"));
                Payloads.putNode(payload, "shippingAddress", Payloads.first(source, "shippingAddress",
                        "shipping_address", "ship_to"));
                Payloads.putNode(payload, "billingAddress", Payloads.first(source, "billingAddress",
                        "billing_address", "bill_to"));
                Payloads.putInstant(payload, "placedAt", occurredAt);
            }
            case OrderFulfilled -> {
                occurredAt = Payloads.instantOr(source, raw.receivedAt(), "fulfilledAt", "fulfilled_at",
                        "closed_at", "completed_at", "updated_at");
                payload.set("fulfilledLines", fulfilledLines(source));
                Payloads.putInstant(payload, "fulfilledAt", occurredAt);
            }
            case OrderCancelled -> {
                occurredAt = Payloads.instantOr(source, raw.receivedAt(), "cancelledAt", "cancelled_at",
                        "canceled_at", "updated_at");
                Payloads.putText(payload, "reason", Payloads.text(source, "reason", "cancel_reason",
                        "cancellation_reason"));
                Payloads.putText(payload, "cancelledBy", upper(Payloads.textOr(source, "MERCHANT",
                        "cancelledBy", "cancelled_by", "canceled_by")));
                Payloads.putInstant(payload, "cancelledAt", occurredAt);
            }
            default -> throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "OrderSystemAdapter does not produce " + eventType);
        }

        return envelope(raw, eventType, orderId, occurredAt, observedAt, payload);
    }

    /**
     * Normalizes order lines into the catalog shape
     * {@code {sku, description, quantity, unitPrice, lineTotal}}.
     *
     * <p>When a source quotes only a unit price, the line total is computed with
     * {@link Money#multiply(long)} - integer arithmetic on minor units, never a multiplication in
     * floating point.
     */
    private ArrayNode lines(JsonNode source, String currency) {
        ArrayNode out = Payloads.array();
        JsonNode lines = Payloads.first(source, "lines", "line_items", "lineItems", "items");
        if (lines == null || !lines.isArray()) {
            return out;
        }
        for (JsonNode line : lines) {
            ObjectNode node = out.addObject();
            Payloads.putText(node, "sku", Payloads.text(line, "sku", "variant_sku", "product_code",
                    "item_code"));
            Payloads.putText(node, "description", Payloads.text(line, "description", "title", "name"));
            int quantity = Math.max(1, orDefault(Payloads.integer(line, "quantity", "qty"), 1));
            node.put("quantity", quantity);
            Money unitPrice = Payloads.money(line, currency, "unitPrice", "unit_price", "price");
            Money lineTotal = Payloads.money(line, currency, "lineTotal", "line_total", "total",
                    "total_price");
            if (lineTotal == null && unitPrice != null) {
                lineTotal = unitPrice.multiply(quantity);
            }
            if (unitPrice == null && lineTotal != null && quantity > 0
                    && lineTotal.amountMinor() % quantity == 0) {
                unitPrice = Money.of(lineTotal.amountMinor() / quantity, lineTotal.currency());
            }
            Payloads.putMoney(node, "unitPrice", unitPrice);
            Payloads.putMoney(node, "lineTotal", lineTotal);
            node.put("digitalGood", Payloads.bool(line, false, "digitalGood", "digital",
                    "requires_shipping_false", "is_digital"));
        }
        return out;
    }

    private ArrayNode fulfilledLines(JsonNode source) {
        ArrayNode out = Payloads.array();
        JsonNode lines = Payloads.first(source, "fulfilledLines", "fulfilled_lines", "line_items",
                "fulfillments.0.line_items", "items");
        if (lines == null || !lines.isArray()) {
            return out;
        }
        for (JsonNode line : lines) {
            ObjectNode node = out.addObject();
            Payloads.putText(node, "sku", Payloads.text(line, "sku", "variant_sku", "product_code"));
            node.put("quantity", Math.max(1, orDefault(Payloads.integer(line, "quantity", "qty"), 1)));
        }
        return out;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
