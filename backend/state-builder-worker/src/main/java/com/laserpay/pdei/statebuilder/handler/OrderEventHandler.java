package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.OrderLineEntity;
import com.laserpay.pdei.persistence.repository.OrderLineRepository;
import com.laserpay.pdei.persistence.repository.OrderRepository;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Projects the ORDER aggregate, its line items, and the {@code ORDER_RECORD} artifact.
 *
 * <h2>Why line items are projected rather than left in the payload</h2>
 *
 * A {@code PRODUCT_NOT_AS_DESCRIBED} representment argues about a specific SKU at a specific price,
 * and a partial-fulfilment argument needs to compare ordered quantity against shipped quantity. Both
 * are joins, and both are impossible against a JSONB blob without turning every query into a
 * document scan. So lines land in {@code order_lines}, keyed {@code {orderId}-L{lineNumber}} exactly
 * as the schema's id convention specifies.
 *
 * <p>Line writes are upserts on that deterministic key rather than delete-then-insert: a redelivered
 * {@code OrderCreated} must not briefly empty an order's lines, because a readiness computation
 * running concurrently would see a transaction with no order record and score it as a gap.
 */
public class OrderEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final TransactionProjection transactionProjection;
    private final DerivedEvidenceService derivedEvidence;
    private final String defaultCurrency;

    public OrderEventHandler(OrderRepository orders,
                             OrderLineRepository orderLines,
                             TransactionProjection transactionProjection,
                             DerivedEvidenceService derivedEvidence,
                             String defaultCurrency) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.transactionProjection = transactionProjection;
        this.derivedEvidence = derivedEvidence;
        this.defaultCurrency = defaultCurrency;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.OrderCreated, EventType.OrderFulfilled, EventType.OrderCancelled);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String orderId = event.aggregateId();
        String transactionId = CanonicalPayloads.text(payload, "transactionId");
        String customerId = CanonicalPayloads.text(payload, "customerId");
        Money orderTotal = CanonicalPayloads.money(payload, "orderTotal", "amount");

        // orders.transaction_id is nullable: an order may legitimately exist before its payment.
        if (transactionId != null) {
            transactionProjection.ensure(event, transactionId, customerId, orderTotal);
        }

        String currency = orderTotal != null ? orderTotal.currency() : defaultCurrency;
        OrderEntity entity = orders.findById(orderId).orElse(null);
        if (entity == null) {
            entity = new OrderEntity();
            entity.setId(orderId);
            entity.setMerchantId(event.merchantId());
            entity.setAmount(MoneyEmbeddable.zero(currency));
            entity.setTaxAmount(MoneyEmbeddable.zero(currency));
            entity.setShippingAmount(MoneyEmbeddable.zero(currency));
            entity.setStatus("CREATED");
            entity.setPlacedAt(event.occurredAt());
        }

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            log.debug("ignoring {} {} for order {}: older than the applied watermark",
                    event.eventType(), event.eventId(), orderId);
            return;
        }

        if (transactionId != null) {
            entity.setTransactionId(transactionId);
        }
        if (customerId != null) {
            entity.setCustomerId(customerId);
        }

        switch (event.eventType()) {
            case OrderCreated -> {
                entity.setStatus("CREATED");
                entity.setPlacedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "placedAt"), event.occurredAt()));
                if (orderTotal != null) {
                    entity.setAmount(MoneyEmbeddable.of(orderTotal));
                }
                Money tax = CanonicalPayloads.money(payload, "taxTotal");
                if (tax != null) {
                    entity.setTaxAmount(MoneyEmbeddable.of(tax));
                }
                Money shipping = CanonicalPayloads.money(payload, "shippingTotal");
                if (shipping != null) {
                    entity.setShippingAmount(MoneyEmbeddable.of(shipping));
                }
                entity.setShippingAddress(CanonicalPayloads.objectMap(payload, "shippingAddress"));
                entity.setBillingAddress(CanonicalPayloads.objectMap(payload, "billingAddress"));
            }
            case OrderFulfilled -> {
                entity.setStatus("FULFILLED");
                entity.setFulfilledAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "fulfilledAt"), event.occurredAt()));
            }
            case OrderCancelled -> {
                entity.setStatus("CANCELLED");
                entity.setCancelledAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "cancelledAt"), event.occurredAt()));
            }
            default -> throw new IllegalStateException(
                    "OrderEventHandler received " + event.eventType());
        }

        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        orders.save(entity);

        if (event.eventType() == EventType.OrderCreated) {
            upsertLines(orderId, payload, currency);
            derivedEvidence.derive(event, EvidenceType.ORDER_RECORD,
                    entity.getTransactionId() != null ? entity.getTransactionId() : transactionId,
                    orderId,
                    "Order " + orderId + " placed"
                            + (orderTotal == null ? "" : " for " + orderTotal.toDisplayString()));
        }
    }

    /**
     * Writes the order's lines, keyed {@code {orderId}-L{n}} so a redelivery overwrites rather than
     * duplicates. Lines the payload no longer contains are left alone: an order amendment is its own
     * event, and inferring a deletion from an absent array would silently drop history.
     */
    private void upsertLines(String orderId, JsonNode payload, String currency) {
        JsonNode lines = payload.get("lines");
        if (lines == null || !lines.isArray()) {
            return;
        }
        int lineNumber = 0;
        for (JsonNode line : lines) {
            lineNumber++;
            String lineId = orderId + "-L" + lineNumber;
            OrderLineEntity entity = orderLines.findById(lineId).orElseGet(() -> {
                OrderLineEntity created = new OrderLineEntity();
                created.setId(lineId);
                created.setOrderId(orderId);
                return created;
            });
            entity.setLineNumber(lineNumber);
            entity.setSku(CanonicalPayloads.text(line, "sku"));
            entity.setDescription(CanonicalPayloads.text(line, "description"));

            Integer quantity = CanonicalPayloads.integer(line, "quantity");
            entity.setQuantity(quantity == null || quantity < 1 ? 1 : quantity);

            Money unitPrice = CanonicalPayloads.money(line, "unitPrice");
            Money lineTotal = CanonicalPayloads.money(line, "lineTotal");
            if (lineTotal == null && unitPrice != null) {
                lineTotal = unitPrice.multiply(entity.getQuantity());
            }
            if (unitPrice == null && lineTotal != null) {
                unitPrice = lineTotal;
            }
            entity.setUnitPrice(MoneyEmbeddable.of(
                    unitPrice == null ? Money.zero(currency) : unitPrice));
            entity.setLineTotal(MoneyEmbeddable.of(
                    lineTotal == null ? Money.zero(currency) : lineTotal));
            entity.setDigitalGood(CanonicalPayloads.bool(line, "digitalGood", false));
            orderLines.save(entity);
        }
    }

    private static java.time.Instant firstNonNull(java.time.Instant candidate,
                                                  java.time.Instant fallback) {
        return candidate != null ? candidate : fallback;
    }
}
