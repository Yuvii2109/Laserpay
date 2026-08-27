package com.laserpay.pdei.readiness.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Answers the one question every inbound event raises for this worker: <em>which transaction does
 * this change affect?</em>
 *
 * <p>Readiness is a property of a transaction, but events arrive about payments, orders, shipments,
 * deliveries, refunds, communications, evidence and disputes. Resolution is tried in order of
 * cost:
 *
 * <ol>
 *   <li>the aggregate <em>is</em> the transaction;</li>
 *   <li>the payload carries {@code transactionId} - the normal case, because normalization-worker
 *       denormalises it onto every canonical event it can;</li>
 *   <li>a single indexed lookup on the owning table.</li>
 * </ol>
 *
 * <p>An unresolvable event is not an error. It routinely means the aggregate row has not been
 * written yet (state-builder-worker is behind, or the event genuinely arrived out of order), and
 * the correct response is to do nothing and let the next event or the at-risk scanner catch up -
 * never to guess at a transaction id.
 */
public class TransactionResolver {

    private static final Logger log = LoggerFactory.getLogger(TransactionResolver.class);

    private static final String FIELD_TRANSACTION_ID = "transactionId";
    private static final String FIELD_REASON_CODE = "reasonCode";

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /** The transaction whose readiness this event can change, if it can be determined. */
    public Optional<String> resolve(CanonicalEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        if (event.aggregateType() == AggregateType.TRANSACTION) {
            return Optional.ofNullable(event.aggregateId());
        }
        Optional<String> fromPayload = payloadTransactionId(event.payload());
        if (fromPayload.isPresent()) {
            return fromPayload;
        }
        return lookup(event.aggregateType(), event.aggregateId());
    }

    /**
     * Dispute events carry the reason code that readiness must be scored against; every other event
     * scores against the merchant baseline profile (PLATFORM-CONTRACT section 7).
     */
    public Optional<DisputeReasonCode> reasonCode(CanonicalEvent event) {
        if (event == null || event.payload() == null) {
            return Optional.empty();
        }
        JsonNode node = event.payload().get(FIELD_REASON_CODE);
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DisputeReasonCode.valueOf(node.asText()));
        } catch (IllegalArgumentException e) {
            log.debug("unknown reason code on event {}: {}", event.eventId(), node.asText());
            return Optional.empty();
        }
    }

    private static Optional<String> payloadTransactionId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        JsonNode node = payload.get(FIELD_TRANSACTION_ID);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(node.asText());
    }

    /**
     * One indexed single-row lookup per aggregate type. Every one of these columns is either the
     * primary key or covered by an index created in V2, V3 or V5.
     */
    private Optional<String> lookup(AggregateType aggregateType, String aggregateId) {
        if (aggregateType == null || aggregateId == null || aggregateId.isBlank()) {
            return Optional.empty();
        }
        String sql = switch (aggregateType) {
            case PAYMENT -> "SELECT transaction_id FROM pdei.payments WHERE payment_id = :id";
            case ORDER -> "SELECT transaction_id FROM pdei.orders WHERE order_id = :id";
            case SHIPMENT -> "SELECT transaction_id FROM pdei.shipments WHERE shipment_id = :id";
            case DELIVERY -> "SELECT transaction_id FROM pdei.deliveries WHERE delivery_id = :id";
            case REFUND -> "SELECT transaction_id FROM pdei.refunds WHERE refund_id = :id";
            case COMMUNICATION ->
                    "SELECT transaction_id FROM pdei.communications WHERE communication_id = :id";
            case EVIDENCE -> "SELECT transaction_id FROM pdei.evidence WHERE evidence_id = :id";
            case DISPUTE -> "SELECT transaction_id FROM pdei.disputes WHERE dispute_id = :id";
            // MERCHANT, CUSTOMER, POLICY and CASE are not scoped to a single transaction: a policy
            // change affects a whole merchant and is handled by the at-risk scanner, not by a
            // point recomputation.
            case MERCHANT, CUSTOMER, POLICY, CASE, TRANSACTION -> null;
        };
        if (sql == null) {
            return Optional.empty();
        }
        try {
            List<String> ids = jdbc.queryForList(sql, new MapSqlParameterSource("id", aggregateId),
                    String.class);
            return ids.stream().filter(Objects::nonNull).findFirst();
        } catch (RuntimeException e) {
            log.warn("could not resolve the transaction for {} {}: {}", aggregateType, aggregateId,
                    e.toString());
            return Optional.empty();
        }
    }
}
