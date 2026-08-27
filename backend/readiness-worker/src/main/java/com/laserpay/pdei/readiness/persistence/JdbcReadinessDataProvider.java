package com.laserpay.pdei.readiness.persistence;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.readiness.ReadinessDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Feeds {@code evidence-core}'s {@code ReadinessEngine} from the real PDEI schema.
 *
 * <p>{@code ReadinessEngine.score} is a pure function; {@code compute} is the shell that gathers
 * its input through this four-method port. Implementing the port here rather than depending on the
 * generic SPI adapters keeps the worker's read path narrow (four queries, all indexed) and, as
 * documented in this module's {@code context.md}, avoids the column-name divergence in
 * {@code evidence-core}'s JDBC adapters.
 *
 * <p>Everything read here is state written by {@code state-builder-worker} and
 * {@code document-processor-service}. This class writes nothing: readiness observes financial
 * state, it never authors it.
 */
public class JdbcReadinessDataProvider implements ReadinessDataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcReadinessDataProvider.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final PolicyEngine policyEngine;

    public JdbcReadinessDataProvider(NamedParameterJdbcTemplate jdbc, PolicyEngine policyEngine) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine must not be null");
    }

    @Override
    public Optional<String> merchantIdFor(String transactionId) {
        List<String> merchants = jdbc.queryForList(
                "SELECT merchant_id FROM pdei.transactions WHERE transaction_id = :transactionId",
                new MapSqlParameterSource("transactionId", transactionId), String.class);
        return merchants.stream().findFirst();
    }

    /**
     * Every artifact attached to the transaction, whatever its status.
     *
     * <p>Superseded and invalidated rows are deliberately included: the engine needs to see them to
     * raise VERSION_CONFLICT gaps and to explain a requirement as "present but not usable" rather
     * than "not attached". Filtering here would hide exactly the interesting cases.
     */
    @Override
    public List<EvidenceView> evidenceFor(String transactionId) {
        return jdbc.query("""
                SELECT e.evidence_id, e.merchant_id, e.transaction_id, e.type, e.status, e.source,
                       e.object_key, e.sha256, e.current_version, e.filename, e.content_type,
                       e.size_bytes, e.summary, e.source_event_id, e.related_entity_id,
                       e.integrity_ok, e.provenance, e.created_at, e.observed_at, e.expires_at,
                       e.metadata,
                       parent.evidence_id AS parent_evidence_id
                  FROM pdei.evidence e
                  LEFT JOIN pdei.evidence parent ON parent.superseded_by = e.evidence_id
                 WHERE e.transaction_id = :transactionId
                 ORDER BY e.created_at, e.evidence_id
                """, new MapSqlParameterSource("transactionId", transactionId), EVIDENCE_MAPPER);
    }

    @Override
    public Optional<TransactionFacts> factsFor(String transactionId) {
        List<TransactionHeader> headers = jdbc.query("""
                SELECT transaction_id, merchant_id, customer_id, amount_minor, currency, status, created_at
                  FROM pdei.transactions
                 WHERE transaction_id = :transactionId
                """, new MapSqlParameterSource("transactionId", transactionId), HEADER_MAPPER);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        TransactionHeader header = headers.get(0);
        MapSqlParameterSource params = new MapSqlParameterSource("transactionId", transactionId);

        return Optional.of(new TransactionFacts(
                header.transactionId(),
                header.merchantId(),
                header.customerId(),
                header.amount(),
                header.status(),
                header.createdAt(),
                payments(params),
                orders(params),
                shipments(params),
                deliveries(params),
                refunds(params),
                communications(params)));
    }

    /**
     * The policy in force. Delegated to {@code PolicyEngine}, which falls back to the deterministic
     * default matrix whenever a merchant has published no policy of its own - so readiness is always
     * scored against something explicit, never against nothing.
     */
    @Override
    public PolicyView policyFor(String merchantId, DisputeReasonCode reasonCode) {
        return policyEngine.applicablePolicy(merchantId, reasonCode);
    }

    // --- fact loaders ---------------------------------------------------------------------------

    private List<TransactionFacts.PaymentFact> payments(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT payment_id, status, amount_minor, currency, psp_reference,
                       created_at, authorized_at, captured_at, avs_result, cvv_result
                  FROM pdei.payments
                 WHERE transaction_id = :transactionId
                 ORDER BY occurred_at, payment_id
                """, params, (rs, i) -> new TransactionFacts.PaymentFact(
                rs.getString("payment_id"),
                rs.getString("status"),
                money(rs, "amount_minor", "currency"),
                rs.getString("psp_reference"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "authorized_at"),
                Sql.instant(rs, "captured_at"),
                rs.getString("avs_result"),
                rs.getString("cvv_result")));
    }

    private List<TransactionFacts.OrderFact> orders(MapSqlParameterSource params) {
        List<OrderRow> rows = jdbc.query("""
                SELECT order_id, status, amount_minor, currency, shipping_address, placed_at, fulfilled_at
                  FROM pdei.orders
                 WHERE transaction_id = :transactionId
                 ORDER BY placed_at, order_id
                """, params, (rs, i) -> new OrderRow(
                rs.getString("order_id"),
                rs.getString("status"),
                money(rs, "amount_minor", "currency"),
                rs.getString("shipping_address"),
                Sql.instant(rs, "placed_at"),
                Sql.instant(rs, "fulfilled_at")));

        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<TransactionFacts.OrderLineFact>> linesByOrder = orderLines(
                rows.stream().map(OrderRow::orderId).toList());

        List<TransactionFacts.OrderFact> orders = new ArrayList<>(rows.size());
        for (OrderRow row : rows) {
            orders.add(new TransactionFacts.OrderFact(row.orderId(), row.status(), row.total(),
                    row.shippingAddress(), row.placedAt(), row.fulfilledAt(),
                    linesByOrder.getOrDefault(row.orderId(), List.of())));
        }
        return List.copyOf(orders);
    }

    private Map<String, List<TransactionFacts.OrderLineFact>> orderLines(List<String> orderIds) {
        Map<String, List<TransactionFacts.OrderLineFact>> byOrder = new LinkedHashMap<>();
        jdbc.query("""
                SELECT order_id, order_line_id, sku, description, quantity,
                       unit_price_amount_minor, unit_price_currency
                  FROM pdei.order_lines
                 WHERE order_id IN (:orderIds)
                 ORDER BY order_id, line_number
                """, new MapSqlParameterSource("orderIds", Sql.nonEmpty(orderIds)),
                (RowMapper<Void>) (rs, i) -> {
                    byOrder.computeIfAbsent(rs.getString("order_id"), key -> new ArrayList<>())
                            .add(new TransactionFacts.OrderLineFact(
                                    rs.getString("order_line_id"),
                                    rs.getString("sku"),
                                    rs.getString("description"),
                                    rs.getInt("quantity"),
                                    money(rs, "unit_price_amount_minor", "unit_price_currency")));
                    return null;
                });
        return byOrder;
    }

    private List<TransactionFacts.ShipmentFact> shipments(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT s.shipment_id, s.order_id, s.carrier, s.tracking_number, s.status,
                       s.destination_address, s.created_at, s.shipped_at,
                       COALESCE(q.quantity, 0) AS quantity
                  FROM pdei.shipments s
                  LEFT JOIN (
                       SELECT ol.order_id, SUM(ol.quantity) AS quantity
                         FROM pdei.order_lines ol
                        GROUP BY ol.order_id
                  ) q ON q.order_id = s.order_id
                 WHERE s.transaction_id = :transactionId
                 ORDER BY s.created_at, s.shipment_id
                """, params, (rs, i) -> new TransactionFacts.ShipmentFact(
                rs.getString("shipment_id"),
                rs.getString("order_id"),
                rs.getString("carrier"),
                rs.getString("tracking_number"),
                rs.getString("status"),
                rs.getString("destination_address"),
                rs.getInt("quantity"),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "shipped_at")));
    }

    private List<TransactionFacts.DeliveryFact> deliveries(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT d.delivery_id, d.shipment_id, d.status, d.signed_by, d.signature_captured,
                       d.proof_object_key, d.delivered_at, s.destination_address
                  FROM pdei.deliveries d
                  LEFT JOIN pdei.shipments s ON s.shipment_id = d.shipment_id
                 WHERE d.transaction_id = :transactionId
                 ORDER BY d.delivered_at NULLS LAST, d.delivery_id
                """, params, (rs, i) -> new TransactionFacts.DeliveryFact(
                rs.getString("delivery_id"),
                rs.getString("shipment_id"),
                rs.getString("status"),
                rs.getString("signed_by"),
                rs.getString("destination_address"),
                proofType(rs),
                Sql.instant(rs, "delivered_at")));
    }

    private List<TransactionFacts.RefundFact> refunds(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT refund_id, payment_id, status, amount_minor, currency, requested_at, processed_at
                  FROM pdei.refunds
                 WHERE transaction_id = :transactionId
                 ORDER BY requested_at, refund_id
                """, params, (rs, i) -> new TransactionFacts.RefundFact(
                rs.getString("refund_id"),
                rs.getString("payment_id"),
                rs.getString("status"),
                money(rs, "amount_minor", "currency"),
                Sql.instant(rs, "requested_at"),
                Sql.instant(rs, "processed_at")));
    }

    private List<TransactionFacts.CommunicationFact> communications(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT communication_id, channel, direction, subject, body, occurred_at
                  FROM pdei.communications
                 WHERE transaction_id = :transactionId
                 ORDER BY occurred_at, communication_id
                """, params, (rs, i) -> new TransactionFacts.CommunicationFact(
                rs.getString("communication_id"),
                rs.getString("channel"),
                rs.getString("direction"),
                rs.getString("subject"),
                rs.getString("body"),
                Sql.instant(rs, "occurred_at")));
    }

    // --- mappers --------------------------------------------------------------------------------

    private static final RowMapper<EvidenceView> EVIDENCE_MAPPER = (rs, i) -> new EvidenceView(
            rs.getString("evidence_id"),
            rs.getString("merchant_id"),
            rs.getString("transaction_id"),
            Sql.enumValue(rs, "type", EvidenceType.class),
            Sql.enumValue(rs, "status", EvidenceStatus.class),
            Sql.enumValue(rs, "source", EvidenceSource.class),
            rs.getString("object_key"),
            rs.getString("sha256"),
            rs.getInt("current_version"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("summary"),
            rs.getString("source_event_id"),
            rs.getString("parent_evidence_id"),
            rs.getString("related_entity_id"),
            qualityScore(rs.getString("metadata")),
            provenanceVerified(rs),
            Sql.instant(rs, "created_at"),
            Sql.instant(rs, "observed_at"),
            Sql.instant(rs, "expires_at"));

    private static final RowMapper<TransactionHeader> HEADER_MAPPER = (rs, i) -> new TransactionHeader(
            rs.getString("transaction_id"),
            rs.getString("merchant_id"),
            rs.getString("customer_id"),
            money(rs, "amount_minor", "currency"),
            rs.getString("status"),
            Sql.instant(rs, "created_at"));

    /**
     * Money is always read as {@code (BIGINT amount_minor, CHAR(3) currency)}. There is no path in
     * this class that produces a {@code double} or a {@code BigDecimal} for a monetary value
     * (non-negotiable rule 4).
     */
    private static Money money(ResultSet rs, String amountColumn, String currencyColumn) throws SQLException {
        long amountMinor = rs.getLong(amountColumn);
        if (rs.wasNull()) {
            return null;
        }
        String currency = rs.getString(currencyColumn);
        return currency == null ? null : Money.of(amountMinor, currency.trim());
    }

    /**
     * Provenance is verifiable when the row was integrity-checked (or never had bytes to check) and
     * carries the source event that produced it. {@code EvidenceView.hasVerifiableProvenance} adds
     * the remaining conditions (hash and source present).
     */
    private static boolean provenanceVerified(ResultSet rs) throws SQLException {
        boolean integrityOk = rs.getBoolean("integrity_ok");
        boolean integrityKnown = !rs.wasNull();
        String provenance = rs.getString("provenance");
        boolean hasProvenance = provenance != null && !provenance.isBlank();
        return (integrityKnown ? integrityOk : hasProvenance) && rs.getString("source_event_id") != null;
    }

    /**
     * Extraction quality, written into {@code evidence.metadata} by
     * {@code document-processor-service} as {@code qualityScore}. Absent means "not assessed", which
     * the gap detector reads as "do not raise a LOW_QUALITY gap" rather than "bad".
     */
    private static double qualityScore(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return 0.0d;
        }
        try {
            var node = com.laserpay.pdei.common.json.Json.readTree(metadata);
            return node != null && node.hasNonNull("qualityScore") ? node.get("qualityScore").asDouble() : 0.0d;
        } catch (RuntimeException e) {
            log.debug("unparseable evidence metadata, treating quality as unassessed: {}", e.toString());
            return 0.0d;
        }
    }

    private static String proofType(ResultSet rs) throws SQLException {
        if (rs.getBoolean("signature_captured")) {
            return "SIGNATURE";
        }
        return rs.getString("proof_object_key") != null ? "PHOTO" : null;
    }

    private record TransactionHeader(String transactionId, String merchantId, String customerId,
                                     Money amount, String status, java.time.Instant createdAt) {
    }

    private record OrderRow(String orderId, String status, Money total, String shippingAddress,
                            java.time.Instant placedAt, java.time.Instant fulfilledAt) {
    }
}
