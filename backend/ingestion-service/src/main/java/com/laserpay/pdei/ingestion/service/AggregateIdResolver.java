package com.laserpay.pdei.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.ingestion.model.IngestRequest;
import java.util.List;

/**
 * Works out which aggregate a submitted raw event is about, because the Kafka partition key is
 * {@code merchantId + ":" + aggregateId} and that key is what makes per-entity ordering true
 * (PLATFORM-CONTRACT section 4).
 *
 * <p>Ingestion is not allowed to <em>interpret</em> a source body - that is normalization-worker's
 * job - but it is allowed to <em>look for an identifier</em> in it, which is a mechanical lookup
 * rather than a semantic translation. The distinction matters: getting this wrong costs ordering,
 * not correctness, and every consumer tolerates out-of-order events anyway.
 *
 * <p><strong>Priority order.</strong> Explicit {@code aggregateId} on the submission wins - an
 * adapter that knows is always right. Otherwise the body is searched for the aggregate-specific id
 * fields in the order below, which mirrors {@code AggregateType} granularity: the most specific
 * aggregate (a payment, an order) before the transaction that contains it. A {@code PaymentCaptured}
 * body carrying both {@code paymentId} and {@code transactionId} keys on the payment, so that all
 * payment events for that payment stay ordered relative to each other.
 *
 * <p>Returns null when nothing is found; the caller then falls back to
 * {@code RawEventEnvelope.partitionKey()}, which is merchant-scoped and stable per fact.
 */
final class AggregateIdResolver {

    /**
     * Body fields inspected, most specific aggregate first. camelCase and snake_case are both
     * checked; source systems are inconsistent about it and this is not a hill worth dying on.
     */
    private static final List<String> ID_FIELDS = List.of(
            "aggregateId",
            "paymentId",
            "orderId",
            "shipmentId",
            "deliveryId",
            "refundId",
            "communicationId",
            "evidenceId",
            "disputeId",
            "caseId",
            "transactionId",
            "customerId");

    private AggregateIdResolver() {
    }

    /** @return the resolved aggregate id, or null when the submission does not reveal one */
    static String resolve(IngestRequest request) {
        if (request == null) {
            return null;
        }
        if (isPresent(request.aggregateId())) {
            return request.aggregateId().trim();
        }
        JsonNode body = request.body();
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String field : ID_FIELDS) {
            String value = textOf(body, field);
            if (value != null) {
                return value;
            }
            String value2 = textOf(body, toSnakeCase(field));
            if (value2 != null) {
                return value2;
            }
        }
        // Last resort: a bare "id". Deliberately last - many sources use "id" for the delivery
        // rather than for the aggregate, and keying on a delivery id would put every event for one
        // payment on a different partition, which is precisely the failure this method exists to avoid.
        return textOf(body, "id");
    }

    private static String textOf(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return isPresent(value) ? value.trim() : null;
    }

    private static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
