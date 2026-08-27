package com.laserpay.pdei.statebuilder.projection;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.persistence.entity.CustomerEntity;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.persistence.repository.CustomerRepository;
import com.laserpay.pdei.persistence.repository.MerchantRepository;
import com.laserpay.pdei.persistence.repository.OrderRepository;
import com.laserpay.pdei.persistence.repository.PaymentRepository;
import com.laserpay.pdei.persistence.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates the placeholder rows that make out-of-order projection possible.
 *
 * <h2>The problem</h2>
 *
 * The schema is relationally honest: {@code payments.transaction_id}, {@code shipments.order_id},
 * {@code deliveries.shipment_id} and {@code customers.merchant_id} are real foreign keys. The event
 * stream is not ordered across aggregates - {@code docs/event-catalog.md} §12 says so explicitly:
 * "A {@code ShipmentDelivered} may be processed before the {@code OrderCreated} it belongs to."
 *
 * <p>Three options existed. Drop the foreign keys (loses the guarantee that makes Postgres worth
 * using). Buffer events until their parents arrive (an unbounded queue and a new failure mode).
 * Or create a minimal parent row on demand. This class is the third.
 *
 * <h2>Stub rows</h2>
 *
 * A stub satisfies the foreign key and asserts nothing else: zero money, the earliest plausible
 * status, and timestamps taken from the event that forced its creation. It carries
 * {@code metadata.pdeiStub = true} and, crucially, <strong>no watermark</strong> - so when the real
 * event for that aggregate arrives, however late, {@link ProjectionWatermark} lets it through and
 * the stub is filled in properly.
 *
 * <p>Stubs are visible rather than hidden: {@code metadata.pdeiStub} makes "how much of this
 * projection is inferred?" an SQL query, which matters when the answer feeds a dispute decision.
 */
public class ReferenceData {

    private static final Logger log = LoggerFactory.getLogger(ReferenceData.class);

    /** Default country for a merchant this worker had to invent. Only ever used on a stub. */
    private static final String DEFAULT_COUNTRY = "IN";

    private final MerchantRepository merchants;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final PaymentRepository payments;
    private final String defaultCurrency;

    public ReferenceData(MerchantRepository merchants,
                         CustomerRepository customers,
                         OrderRepository orders,
                         ShipmentRepository shipments,
                         PaymentRepository payments,
                         String defaultCurrency) {
        this.merchants = merchants;
        this.customers = customers;
        this.orders = orders;
        this.shipments = shipments;
        this.payments = payments;
        this.defaultCurrency = defaultCurrency;
    }

    /**
     * Guarantees a merchant row exists.
     *
     * <p>In production merchants are onboarded before their events arrive; in a simulation run or a
     * replay into an empty database they are not. Rather than dead-lettering every event of an
     * unknown merchant, the tenant root is created from what the event knows.
     */
    public MerchantEntity ensureMerchant(String merchantId, String currency) {
        return merchants.findById(merchantId).orElseGet(() -> {
            MerchantEntity entity = new MerchantEntity();
            entity.setId(merchantId);
            entity.setLegalName(merchantId);
            entity.setDisplayName(merchantId);
            entity.setCountry(DEFAULT_COUNTRY);
            entity.setDefaultCurrency(currency == null ? defaultCurrency : currency);
            entity.setStatus("ACTIVE");
            entity.setTimezone("UTC");
            entity.setMetadata(stubMetadata("created by state-builder-worker from an event stream"));
            log.info("created stub merchant {} (first event for an unknown merchant)", merchantId);
            return merchants.save(entity);
        });
    }

    /** Guarantees a customer row exists, creating a stub scoped to the merchant when it does not. */
    public CustomerEntity ensureCustomer(String customerId, String merchantId, Instant seenAt) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        return customers.findById(customerId).map(existing -> {
            if (existing.getLastSeenAt() == null || existing.getLastSeenAt().isBefore(seenAt)) {
                existing.setLastSeenAt(seenAt);
            }
            return existing;
        }).orElseGet(() -> {
            CustomerEntity entity = new CustomerEntity();
            entity.setId(customerId);
            entity.setMerchantId(merchantId);
            entity.setFirstSeenAt(seenAt);
            entity.setLastSeenAt(seenAt);
            entity.setMetadata(stubMetadata("created by state-builder-worker from an event stream"));
            return customers.save(entity);
        });
    }

    /**
     * Guarantees an order row exists so a shipment can reference it.
     *
     * <p>The stub's {@code placedAt} is the arriving event's {@code occurredAt}: it is the latest
     * instant by which the order must already have existed, and it is a fact rather than a guess.
     */
    public OrderEntity ensureOrder(String orderId, CanonicalEvent event, String transactionId,
                                   String currency) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        return orders.findById(orderId).orElseGet(() -> {
            OrderEntity entity = new OrderEntity();
            entity.setId(orderId);
            entity.setMerchantId(event.merchantId());
            entity.setTransactionId(transactionId);
            entity.setAmount(MoneyEmbeddable.zero(currency));
            entity.setTaxAmount(MoneyEmbeddable.zero(currency));
            entity.setShippingAmount(MoneyEmbeddable.zero(currency));
            entity.setStatus("CREATED");
            entity.setPlacedAt(event.occurredAt());
            entity.setMetadata(stubMetadata("implied by " + event.eventType() + " " + event.eventId()));
            log.debug("created stub order {} implied by {}", orderId, event.eventType());
            return orders.save(entity);
        });
    }

    /** Guarantees a shipment row exists so a delivery can reference it. */
    public ShipmentEntity ensureShipment(String shipmentId, CanonicalEvent event, String orderId,
                                         String transactionId, String currency) {
        if (shipmentId == null || shipmentId.isBlank()) {
            return null;
        }
        return shipments.findById(shipmentId).orElseGet(() -> {
            ShipmentEntity entity = new ShipmentEntity();
            entity.setId(shipmentId);
            entity.setMerchantId(event.merchantId());
            entity.setOrderId(orderId);
            entity.setTransactionId(transactionId);
            entity.setStatus("CREATED");
            entity.setDeclaredValue(MoneyEmbeddable.zero(currency));
            entity.setMetadata(stubMetadata("implied by " + event.eventType() + " " + event.eventId()));
            log.debug("created stub shipment {} implied by {}", shipmentId, event.eventType());
            return shipments.save(entity);
        });
    }

    /** Guarantees a payment row exists so a refund can reference it. */
    public PaymentEntity ensurePayment(String paymentId, CanonicalEvent event, String transactionId,
                                       String currency) {
        if (paymentId == null || paymentId.isBlank()) {
            return null;
        }
        return payments.findById(paymentId).orElseGet(() -> {
            PaymentEntity entity = new PaymentEntity();
            entity.setId(paymentId);
            entity.setMerchantId(event.merchantId());
            entity.setTransactionId(transactionId);
            entity.setAmount(MoneyEmbeddable.zero(currency));
            entity.setStatus("CREATED");
            entity.setOccurredAt(event.occurredAt());
            entity.setMetadata(stubMetadata("implied by " + event.eventType() + " " + event.eventId()));
            log.debug("created stub payment {} implied by {}", paymentId, event.eventType());
            return payments.save(entity);
        });
    }

    /** Metadata marking a row as inferred, with the reason it had to be. */
    public static Map<String, Object> stubMetadata(String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ProjectionWatermark.Stubs.MARKER, Boolean.TRUE);
        metadata.put("pdeiStubReason", reason);
        return metadata;
    }
}
