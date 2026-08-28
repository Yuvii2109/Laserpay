package com.laserpay.pdei.core.spi.jdbc;

import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter that assembles {@link TransactionFacts} from the seven state tables.
 *
 * <p>Read-only by construction: evidence-core reasons about financial state, it never writes it.
 * Six small indexed queries rather than one wide join - the shapes are one-to-many in several
 * directions and a single join would multiply rows and force de-duplication in Java anyway.</p>
 */
public class JdbcTransactionRepository implements TransactionRepositoryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TransactionFacts.PaymentFact> PAYMENT = (rs, i) ->
            new TransactionFacts.PaymentFact(rs.getString("id"), rs.getString("status"),
                    JdbcSupport.money(rs, "amount_minor", "currency"), rs.getString("psp_reference"),
                    JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "authorized_at"),
                    JdbcSupport.instant(rs, "captured_at"), rs.getString("avs_result"),
                    rs.getString("cvv_result"));

    private static final RowMapper<TransactionFacts.OrderLineFact> ORDER_LINE = (rs, i) ->
            new TransactionFacts.OrderLineFact(rs.getString("id"), rs.getString("sku"),
                    rs.getString("description"), rs.getInt("quantity"),
                    JdbcSupport.money(rs, "unit_price_amount_minor", "unit_price_currency"));

    private static final RowMapper<TransactionFacts.ShipmentFact> SHIPMENT = (rs, i) ->
            new TransactionFacts.ShipmentFact(rs.getString("id"), rs.getString("order_id"),
                    rs.getString("carrier"), rs.getString("tracking_number"), rs.getString("status"),
                    rs.getString("destination_address"), rs.getInt("quantity"),
                    JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "dispatched_at"));

    private static final RowMapper<TransactionFacts.DeliveryFact> DELIVERY = (rs, i) ->
            new TransactionFacts.DeliveryFact(rs.getString("id"), rs.getString("shipment_id"),
                    rs.getString("status"), rs.getString("signed_by"),
                    rs.getString("delivered_to_address"), rs.getString("proof_type"),
                    JdbcSupport.instant(rs, "delivered_at"));

    private static final RowMapper<TransactionFacts.RefundFact> REFUND = (rs, i) ->
            new TransactionFacts.RefundFact(rs.getString("id"), rs.getString("payment_id"),
                    rs.getString("status"), JdbcSupport.money(rs, "amount_minor", "currency"),
                    JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "processed_at"));

    private static final RowMapper<TransactionFacts.CommunicationFact> COMMUNICATION = (rs, i) ->
            new TransactionFacts.CommunicationFact(rs.getString("id"), rs.getString("channel"),
                    rs.getString("direction"), rs.getString("subject"), rs.getString("body"),
                    JdbcSupport.instant(rs, "occurred_at"));

    @Override
    public Optional<TransactionFacts> findFacts(String transactionId) {
        Map<String, Object> params = Map.of("tx", transactionId);
        List<Object[]> header = jdbc.query("""
                SELECT merchant_id, customer_id, amount_minor, currency, status, created_at
                  FROM pdei.transactions WHERE transaction_id = :tx
                """, params, (rs, i) -> new Object[]{
                rs.getString("merchant_id"), rs.getString("customer_id"),
                JdbcSupport.money(rs, "amount_minor", "currency"), rs.getString("status"),
                JdbcSupport.instant(rs, "created_at")});
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = header.get(0);

        List<TransactionFacts.PaymentFact> payments = jdbc.query(
                "SELECT payment_id AS id, p.* FROM pdei.payments p WHERE transaction_id = :tx"
                        + " ORDER BY created_at, payment_id",
                params, PAYMENT);
        List<TransactionFacts.ShipmentFact> shipments = jdbc.query(
                "SELECT shipment_id AS id, s.*, shipped_at AS dispatched_at, 0 AS quantity"
                        + " FROM pdei.shipments s WHERE transaction_id = :tx"
                        + " ORDER BY created_at, shipment_id",
                params, SHIPMENT);
        List<TransactionFacts.DeliveryFact> deliveries = jdbc.query(
                """
                        SELECT d.delivery_id AS id, d.*, s.destination_address AS delivered_to_address,
                               CASE WHEN d.signature_captured THEN 'SIGNATURE'
                                    WHEN d.proof_object_key IS NOT NULL THEN 'DOCUMENT'
                                    ELSE 'UNVERIFIED' END AS proof_type
                          FROM pdei.deliveries d
                          LEFT JOIN pdei.shipments s ON s.shipment_id = d.shipment_id
                         WHERE d.transaction_id = :tx
                         ORDER BY d.delivered_at, d.delivery_id
                        """,
                params, DELIVERY);
        List<TransactionFacts.RefundFact> refunds = jdbc.query(
                "SELECT refund_id AS id, r.* FROM pdei.refunds r WHERE transaction_id = :tx"
                        + " ORDER BY created_at, refund_id",
                params, REFUND);
        List<TransactionFacts.CommunicationFact> communications = jdbc.query(
                "SELECT communication_id AS id, c.* FROM pdei.communications c WHERE transaction_id = :tx"
                        + " ORDER BY occurred_at, communication_id",
                params, COMMUNICATION);
        List<TransactionFacts.OrderFact> orders = loadOrders(transactionId);

        return Optional.of(new TransactionFacts(transactionId, (String) row[0], (String) row[1],
                (com.laserpay.pdei.common.money.Money) row[2], (String) row[3],
                (java.time.Instant) row[4], payments, orders, shipments, deliveries, refunds,
                communications));
    }

    private List<TransactionFacts.OrderFact> loadOrders(String transactionId) {
        Map<String, List<TransactionFacts.OrderLineFact>> linesByOrder = new LinkedHashMap<>();
        jdbc.query("""
                SELECT l.order_line_id AS id, l.* FROM pdei.order_lines l
                  JOIN pdei.orders o ON o.order_id = l.order_id
                 WHERE o.transaction_id = :tx
                 ORDER BY l.line_number, l.order_line_id
                """, Map.of("tx", transactionId), (RowCallbackHandler) rs -> {
            String orderId = rs.getString("order_id");
            linesByOrder.computeIfAbsent(orderId, key -> new ArrayList<>())
                    .add(ORDER_LINE.mapRow(rs, 0));
        });

        return jdbc.query("""
                SELECT order_id AS id, status, amount_minor AS total_amount_minor, currency,
                       shipping_address, created_at, fulfilled_at
                  FROM pdei.orders WHERE transaction_id = :tx ORDER BY created_at, order_id
                """, Map.of("tx", transactionId), (rs, i) -> new TransactionFacts.OrderFact(
                rs.getString("id"), rs.getString("status"),
                JdbcSupport.money(rs, "total_amount_minor", "currency"), rs.getString("shipping_address"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "fulfilled_at"),
                linesByOrder.getOrDefault(rs.getString("id"), List.of())));
    }

    @Override
    public Optional<String> findMerchantId(String transactionId) {
        List<String> rows = jdbc.queryForList(
                "SELECT merchant_id FROM pdei.transactions WHERE transaction_id = :tx",
                Map.of("tx", transactionId), String.class);
        return rows.stream().findFirst();
    }

    @Override
    public boolean exists(String transactionId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM pdei.transactions WHERE transaction_id = :tx",
                Map.of("tx", transactionId), Long.class);
        return count != null && count > 0;
    }
}
