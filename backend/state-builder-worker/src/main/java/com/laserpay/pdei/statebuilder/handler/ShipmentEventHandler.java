package com.laserpay.pdei.statebuilder.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.entity.DeliveryEntity;
import com.laserpay.pdei.persistence.entity.MoneyEmbeddable;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.persistence.repository.DeliveryRepository;
import com.laserpay.pdei.persistence.repository.ShipmentRepository;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.projection.ProjectionWatermark;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.support.CanonicalPayloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Projects the SHIPMENT aggregate, the DELIVERY row a delivered shipment produces, and the two
 * artifacts that decide most {@code GOODS_NOT_RECEIVED} cases.
 *
 * <p>Derived evidence (docs/event-catalog.md §3):
 * <ul>
 *   <li>{@code ShipmentDispatched} -> {@code SHIPPING_RECORD}</li>
 *   <li>{@code ShipmentDelivered} -> {@code DELIVERY_PROOF} — the single most decisive artifact for
 *       {@code GOODS_NOT_RECEIVED}, which is why its absence is a CRITICAL gap.</li>
 * </ul>
 *
 * <h2>Out-of-order in its sharpest form</h2>
 *
 * Carrier events routinely arrive before the order they belong to, and a "delivered" push can
 * overtake the "dispatched" push it logically follows. Two mechanisms handle that: the transaction
 * and order rows are created on demand by {@link ReferenceData}, and the per-row watermark refuses
 * an event older than the row's last applied fact.
 *
 * <p>Geo coordinates are stored as integer micro-degrees, matching {@code deliveries.geo_lat_micro}.
 * The database contains no floating-point columns at all, so nothing can be mistaken for money.
 */
public class ShipmentEventHandler implements AggregateEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventHandler.class);

    private final ShipmentRepository shipments;
    private final DeliveryRepository deliveries;
    private final ReferenceData referenceData;
    private final DerivedEvidenceService derivedEvidence;
    private final String defaultCurrency;

    public ShipmentEventHandler(ShipmentRepository shipments,
                                DeliveryRepository deliveries,
                                ReferenceData referenceData,
                                DerivedEvidenceService derivedEvidence,
                                String defaultCurrency) {
        this.shipments = shipments;
        this.deliveries = deliveries;
        this.referenceData = referenceData;
        this.derivedEvidence = derivedEvidence;
        this.defaultCurrency = defaultCurrency;
    }

    @Override
    public Set<EventType> handles() {
        return EnumSet.of(EventType.ShipmentCreated, EventType.ShipmentDispatched,
                EventType.ShipmentDelivered);
    }

    @Override
    public void handle(CanonicalEvent event) {
        JsonNode payload = event.payload();
        String shipmentId = event.aggregateId();
        String orderId = CanonicalPayloads.text(payload, "orderId");
        Money declaredValue = CanonicalPayloads.money(payload, "declaredValue");
        String currency = declaredValue != null ? declaredValue.currency() : defaultCurrency;

        referenceData.ensureMerchant(event.merchantId(), currency);

        // The transaction may be named by the event, or inherited from the order the shipment
        // belongs to. Either way it stays nullable: shipments.transaction_id allows it.
        String transactionId = CanonicalPayloads.text(payload, "transactionId");
        if (orderId != null) {
            OrderEntity order = referenceData.ensureOrder(orderId, event, transactionId, currency);
            if (transactionId == null && order != null) {
                transactionId = order.getTransactionId();
            }
        }

        ShipmentEntity entity = shipments.findById(shipmentId).orElse(null);
        if (entity == null) {
            entity = new ShipmentEntity();
            entity.setId(shipmentId);
            entity.setMerchantId(event.merchantId());
            entity.setStatus("CREATED");
            entity.setDeclaredValue(MoneyEmbeddable.zero(currency));
        }

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            log.debug("ignoring {} {} for shipment {}: older than the applied watermark",
                    event.eventType(), event.eventId(), shipmentId);
            return;
        }

        if (orderId != null) {
            entity.setOrderId(orderId);
        }
        if (transactionId != null) {
            entity.setTransactionId(transactionId);
        }
        applyIfPresent(payload, "carrier", entity::setCarrier);
        applyIfPresent(payload, "trackingNumber", entity::setTrackingNumber);
        applyIfPresent(payload, "serviceLevel", entity::setServiceLevel);
        if (declaredValue != null) {
            entity.setDeclaredValue(MoneyEmbeddable.of(declaredValue));
        }

        switch (event.eventType()) {
            case ShipmentCreated -> entity.setStatus("CREATED");
            case ShipmentDispatched -> {
                entity.setStatus("DISPATCHED");
                entity.setShippedAt(firstNonNull(
                        CanonicalPayloads.instant(payload, "dispatchedAt"), event.occurredAt()));
                entity.setEstimatedDeliveryAt(
                        CanonicalPayloads.instant(payload, "estimatedDeliveryAt"));
                entity.setDestinationAddress(
                        CanonicalPayloads.objectMap(payload, "destinationAddress"));
            }
            case ShipmentDelivered -> {
                entity.setStatus("DELIVERED");
                entity.setDestinationAddress(firstNonNull(
                        CanonicalPayloads.objectMap(payload, "deliveryAddress"),
                        entity.getDestinationAddress()));
            }
            default -> throw new IllegalStateException(
                    "ShipmentEventHandler received " + event.eventType());
        }

        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        shipments.save(entity);

        if (event.eventType() == EventType.ShipmentDispatched) {
            derivedEvidence.derive(event, EvidenceType.SHIPPING_RECORD, transactionId, shipmentId,
                    "Shipment " + shipmentId + " dispatched"
                            + describeCarrier(payload));
        } else if (event.eventType() == EventType.ShipmentDelivered) {
            projectDelivery(event, payload, shipmentId, transactionId);
            derivedEvidence.derive(event, EvidenceType.DELIVERY_PROOF, transactionId, shipmentId,
                    "Shipment " + shipmentId + " delivered" + describeSignature(payload));
        }
    }

    // --- delivery -------------------------------------------------------------------------------

    /**
     * Writes the {@code deliveries} row. The delivery id comes from the carrier when it supplies
     * one and is otherwise derived from the shipment id by normalization-worker - deterministically,
     * so a redelivered event updates the same row instead of forking a second delivery.
     */
    private void projectDelivery(CanonicalEvent event, JsonNode payload, String shipmentId,
                                 String transactionId) {
        String deliveryId = CanonicalPayloads.text(payload, "deliveryId");
        if (deliveryId == null) {
            deliveryId = deriveDeliveryId(shipmentId);
        }
        if (deliveryId == null) {
            return;
        }

        String finalDeliveryId = deliveryId;
        DeliveryEntity entity = deliveries.findById(finalDeliveryId).orElseGet(() -> {
            DeliveryEntity created = new DeliveryEntity();
            created.setId(finalDeliveryId);
            created.setShipmentId(shipmentId);
            created.setMerchantId(event.merchantId());
            created.setStatus("PENDING");
            created.setAttempts(0);
            return created;
        });

        if (!ProjectionWatermark.shouldApply(entity.getMetadata(), event)) {
            return;
        }

        entity.setTransactionId(transactionId);
        entity.setStatus("DELIVERED");
        entity.setDeliveredAt(firstNonNull(
                CanonicalPayloads.instant(payload, "deliveredAt"), event.occurredAt()));
        entity.setRecipientName(CanonicalPayloads.text(payload, "recipientName", "signedBy"));
        entity.setSignedBy(CanonicalPayloads.text(payload, "signedBy"));
        entity.setSignatureCaptured(isSignatureProof(payload));
        entity.setProofObjectKey(CanonicalPayloads.text(payload, "proofObjectKey"));

        Integer attempts = CanonicalPayloads.integer(payload, "attempts");
        entity.setAttempts(attempts == null ? Math.max(1, entity.getAttempts()) : Math.max(1, attempts));

        JsonNode geo = payload.get("geo");
        if (geo != null && geo.isObject()) {
            entity.setGeoLatMicro(CanonicalPayloads.integer(geo, "latMicro"));
            entity.setGeoLonMicro(CanonicalPayloads.integer(geo, "lonMicro"));
        }

        entity.setMetadata(ProjectionWatermark.stamp(entity.getMetadata(), event));
        deliveries.save(entity);
    }

    /** {@code SHP-771} yields {@code DLV-771}: stable, and identical on every replay. */
    static String deriveDeliveryId(String shipmentId) {
        if (shipmentId == null || shipmentId.isBlank()) {
            return null;
        }
        String bare = shipmentId.startsWith(IdPrefix.SHIPMENT)
                ? shipmentId.substring(IdPrefix.SHIPMENT.length())
                : shipmentId;
        return IdPrefix.DELIVERY + bare;
    }

    private static boolean isSignatureProof(JsonNode payload) {
        String proofType = CanonicalPayloads.text(payload, "proofType");
        return proofType != null && "SIGNATURE".equals(proofType.toUpperCase(Locale.ROOT));
    }

    // --- helpers --------------------------------------------------------------------------------

    private static String describeCarrier(JsonNode payload) {
        String carrier = CanonicalPayloads.text(payload, "carrier");
        String tracking = CanonicalPayloads.text(payload, "trackingNumber");
        if (carrier == null && tracking == null) {
            return "";
        }
        return " via " + (carrier == null ? "carrier" : carrier)
                + (tracking == null ? "" : " (" + tracking + ")");
    }

    private static String describeSignature(JsonNode payload) {
        String signedBy = CanonicalPayloads.text(payload, "signedBy");
        return signedBy == null ? "" : ", signed by " + signedBy;
    }

    private static void applyIfPresent(JsonNode payload, String field,
                                       java.util.function.Consumer<String> setter) {
        String value = CanonicalPayloads.text(payload, field);
        if (value != null) {
            setter.accept(value);
        }
    }

    private static <T> T firstNonNull(T candidate, T fallback) {
        return candidate != null ? candidate : fallback;
    }
}
