package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.normalization.support.Payloads;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Carrier / 3PL tracking events.
 *
 * <p>Logistics is where the decisive artifact for {@code GOODS_NOT_RECEIVED} comes from, so this
 * adapter is deliberately generous about what it accepts and strict about what it preserves:
 * carrier, tracking number, dispatch and delivery instants, signature, proof object key and geo.
 *
 * <p>Geo coordinates are normalized to <em>integer micro-degrees</em> to match the database, which
 * contains no floating-point columns at all - see {@code V2__transactions.sql}. A decimal degree
 * string is shifted by integer arithmetic, never parsed into a {@code double}.
 */
public class LogisticsAdapter extends AbstractSourceAdapter {

    private static final Map<String, EventType> MAPPINGS = Map.ofEntries(
            Map.entry("shipment.created", EventType.ShipmentCreated),
            Map.entry("shipment.booked", EventType.ShipmentCreated),
            Map.entry("shipment.label_created", EventType.ShipmentCreated),
            Map.entry("shipment.dispatched", EventType.ShipmentDispatched),
            Map.entry("shipment.picked_up", EventType.ShipmentDispatched),
            Map.entry("shipment.in_transit", EventType.ShipmentDispatched),
            Map.entry("shipment.out_for_delivery", EventType.ShipmentDispatched),
            Map.entry("shipment.delivered", EventType.ShipmentDelivered),
            Map.entry("delivery.completed", EventType.ShipmentDelivered),
            Map.entry("tracking.delivered", EventType.ShipmentDelivered));

    private static final Set<String> ALIASES = Set.of(
            "LOGISTICS", "CARRIER", "3PL", "shiprocket", "delhivery", "bluedart", "fedex", "dhl");

    /** Micro-degrees: one degree is 1_000_000 units. Matches {@code geo_lat_micro} in the schema. */
    private static final int GEO_SCALE_DIGITS = 6;

    public LogisticsAdapter(String defaultCurrency) {
        super("LOGISTICS", ALIASES, MAPPINGS, defaultCurrency);
    }

    @Override
    public EventSource eventSource() {
        return EventSource.LOGISTICS;
    }

    @Override
    protected CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt) {
        JsonNode wrapped = Payloads.first(raw.body(), "shipment", "data.shipment", "data", "payload");
        JsonNode source = wrapped == null ? raw.body() : wrapped;

        String shipmentId = prefixedFrom(source, IdPrefix.SHIPMENT, "shipmentId", "shipment_id", "id",
                "awb", "waybill");
        ObjectNode payload = Payloads.object();
        Payloads.putText(payload, "shipmentId", shipmentId);
        Payloads.putText(payload, "orderId", prefixedFrom(source, IdPrefix.ORDER, "orderId",
                "order_id", "reference", "client_order_id"));
        Payloads.putText(payload, "transactionId", prefixedFrom(source, IdPrefix.TRANSACTION,
                "transactionId", "transaction_id", "metadata.transaction_id"));

        Instant occurredAt;
        switch (eventType) {
            case ShipmentCreated -> {
                occurredAt = Payloads.instantOr(source, raw.receivedAt(), "createdAt", "created_at",
                        "booked_at", "label_created_at");
                Payloads.putText(payload, "carrier", carrier(raw, source));
                Payloads.putText(payload, "trackingNumber", Payloads.text(source, "trackingNumber",
                        "tracking_number", "awb", "waybill"));
                Payloads.putText(payload, "serviceLevel", Payloads.text(source, "serviceLevel",
                        "service_level", "service", "courier_service"));
                Payloads.putMoney(payload, "declaredValue", Payloads.money(source,
                        Payloads.normalizeCurrency(Payloads.text(source, "currency", "currency_code"),
                                defaultCurrency()),
                        "declaredValue", "declared_value", "invoice_value"));
                Payloads.putNode(payload, "lines", Payloads.first(source, "lines", "items",
                        "line_items"));
                Payloads.putNode(payload, "destinationAddress", Payloads.first(source,
                        "destinationAddress", "destination_address", "delivery_address", "ship_to"));
                Payloads.putInstant(payload, "createdAt", occurredAt);
            }
            case ShipmentDispatched -> {
                occurredAt = Payloads.instantOr(source, raw.receivedAt(), "dispatchedAt",
                        "dispatched_at", "picked_up_at", "shipped_at", "event_time");
                Payloads.putText(payload, "carrier", carrier(raw, source));
                Payloads.putText(payload, "trackingNumber", Payloads.text(source, "trackingNumber",
                        "tracking_number", "awb", "waybill"));
                Payloads.putText(payload, "originHub", Payloads.text(source, "originHub", "origin_hub",
                        "origin", "from_location"));
                Payloads.putInstant(payload, "estimatedDeliveryAt", Payloads.instant(source,
                        "estimatedDeliveryAt", "estimated_delivery_at", "edd", "expected_delivery"));
                Payloads.putInstant(payload, "dispatchedAt", occurredAt);
            }
            case ShipmentDelivered -> {
                occurredAt = Payloads.instantOr(source, raw.receivedAt(), "deliveredAt", "delivered_at",
                        "delivery_time", "event_time");
                Payloads.putText(payload, "deliveryId", deliveryId(source, shipmentId));
                Payloads.putText(payload, "signedBy", Payloads.text(source, "signedBy", "signed_by",
                        "receiver_name", "recipient"));
                Payloads.putText(payload, "proofType", upper(Payloads.textOr(source, "OTHER",
                        "proofType", "proof_type", "pod_type")));
                Payloads.putText(payload, "proofObjectKey", Payloads.text(source, "proofObjectKey",
                        "proof_object_key", "pod_url", "signature_url"));
                Payloads.putNode(payload, "deliveryAddress", Payloads.first(source, "deliveryAddress",
                        "delivery_address", "destination_address", "ship_to"));
                putGeo(payload, source);
                payload.put("attempts", attempts(source));
                Payloads.putInstant(payload, "deliveredAt", occurredAt);
            }
            default -> throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "LogisticsAdapter does not produce " + eventType);
        }

        return envelope(raw, eventType, shipmentId, occurredAt, observedAt, payload);
    }

    private String carrier(RawEventEnvelope raw, JsonNode source) {
        return Payloads.textOr(source, raw.sourceSystem(), "carrier", "courier", "carrier_name",
                "courier_name");
    }

    /**
     * A delivery id when the carrier supplies one, otherwise derived from the shipment id.
     *
     * <p>Deterministic fallback on purpose: the derived id must be identical on every replay of the
     * same event, or the delivery projection would fork into duplicates.
     */
    private String deliveryId(JsonNode source, String shipmentId) {
        String supplied = prefixedFrom(source, IdPrefix.DELIVERY, "deliveryId", "delivery_id");
        if (supplied != null) {
            return supplied;
        }
        if (shipmentId == null) {
            return null;
        }
        String bare = shipmentId.startsWith(IdPrefix.SHIPMENT)
                ? shipmentId.substring(IdPrefix.SHIPMENT.length())
                : shipmentId;
        return IdPrefix.DELIVERY + bare;
    }

    private int attempts(JsonNode source) {
        Integer attempts = Payloads.integer(source, "attempts", "attempt_count", "delivery_attempts");
        return attempts == null ? 1 : Math.max(1, attempts);
    }

    /**
     * Writes {@code geo} as {@code {latMicro, lonMicro}} - integer micro-degrees, matching
     * {@code deliveries.geo_lat_micro}. Sources sending decimal degrees are converted by integer
     * digit shifting so the pipeline stays free of floating point end to end.
     */
    private void putGeo(ObjectNode payload, JsonNode source) {
        JsonNode geo = Payloads.first(source, "geo", "location", "coordinates");
        JsonNode latNode = geo != null ? Payloads.first(geo, "lat", "latitude", "latMicro")
                : Payloads.first(source, "lat", "latitude");
        JsonNode lonNode = geo != null ? Payloads.first(geo, "lon", "lng", "longitude", "lonMicro")
                : Payloads.first(source, "lon", "lng", "longitude");
        Integer lat = microDegrees(latNode);
        Integer lon = microDegrees(lonNode);
        if (lat == null || lon == null) {
            return;
        }
        ObjectNode node = payload.putObject("geo");
        node.put("latMicro", lat.intValue());
        node.put("lonMicro", lon.intValue());
    }

    private Integer microDegrees(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            long value = node.longValue();
            // Already micro-degrees when the magnitude is far beyond a plain degree value.
            return (int) (Math.abs(value) > 1_000 ? value : value * 1_000_000L);
        }
        String text = node.isTextual() ? node.asText()
                : node.isFloatingPointNumber() ? node.asText() : null;
        if (text == null) {
            return null;
        }
        try {
            return (int) shiftDecimal(text, GEO_SCALE_DIGITS);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Integer-only decimal shift: "12.9716" with scale 6 becomes 12971600. */
    private static long shiftDecimal(String text, int scale) {
        String trimmed = text.trim();
        boolean negative = trimmed.startsWith("-");
        if (negative || trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1);
        }
        int dot = trimmed.indexOf('.');
        String whole = dot < 0 ? trimmed : trimmed.substring(0, dot);
        String fraction = dot < 0 ? "" : trimmed.substring(dot + 1);
        if (fraction.length() > scale) {
            fraction = fraction.substring(0, scale);
        }
        StringBuilder padded = new StringBuilder(fraction);
        while (padded.length() < scale) {
            padded.append('0');
        }
        long value = Long.parseLong((whole.isEmpty() ? "0" : whole) + padded);
        return negative ? -value : value;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
