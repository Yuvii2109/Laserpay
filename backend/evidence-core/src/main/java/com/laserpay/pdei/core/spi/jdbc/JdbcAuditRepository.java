package com.laserpay.pdei.core.spi.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.spi.AuditRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter for {@code pdei.audit_events}.
 *
 * <p>Append only. There is no update or delete statement in this class by design: the table is hash
 * chained and any mutation would be detected by {@code AuditRecorder.verifyChain} as tampering.</p>
 *
 * <p>{@code lastHash} orders by {@code occurred_at, id} so that two entries written in the same
 * millisecond still have a deterministic order and the chain stays reproducible.</p>
 */
public class JdbcAuditRepository implements AuditRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AuditEvent> MAPPER = (rs, i) -> new AuditEvent(
            rs.getString("id"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getString("merchant_id"),
            rs.getString("action"),
            rs.getString("actor"),
            JdbcSupport.enumValue(rs, "actor_type", ActorType.class),
            JdbcSupport.instant(rs, "occurred_at"),
            rs.getString("correlation_id"),
            tree(rs.getString("before_json")),
            tree(rs.getString("after_json")),
            rs.getString("previous_hash"),
            rs.getString("hash"));

    private static JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.mapper().readTree(json);
        } catch (Exception e) {
            log.warn("could not parse stored audit payload: {}", e.toString());
            return null;
        }
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.update("""
                INSERT INTO pdei.audit_events (id, entity_type, entity_id, merchant_id, action, actor,
                    actor_type, occurred_at, correlation_id, before_json, after_json, previous_hash, hash)
                VALUES (:id, :entityType, :entityId, :merchantId, :action, :actor, :actorType, :occurredAt,
                    :correlationId, CAST(:before AS jsonb), CAST(:after AS jsonb), :previousHash, :hash)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", event.auditId())
                        .addValue("entityType", event.entityType())
                        .addValue("entityId", event.entityId())
                        .addValue("merchantId", event.merchantId())
                        .addValue("action", event.action())
                        .addValue("actor", event.actor())
                        .addValue("actorType", JdbcSupport.name(event.actorType()))
                        .addValue("occurredAt", JdbcSupport.timestamp(event.occurredAt()))
                        .addValue("correlationId", event.correlationId())
                        .addValue("before", event.before() == null ? null : event.before().toString())
                        .addValue("after", event.after() == null ? null : event.after().toString())
                        .addValue("previousHash", event.previousHash())
                        .addValue("hash", event.hash()));
    }

    @Override
    public Optional<String> lastHash(String merchantId) {
        List<String> hashes = jdbc.queryForList("""
                SELECT hash FROM pdei.audit_events WHERE merchant_id = :merchant
                 ORDER BY occurred_at DESC, id DESC LIMIT 1
                """, Map.of("merchant", merchantId), String.class);
        return hashes.stream().findFirst();
    }

    @Override
    public List<AuditEvent> findChain(String merchantId, int limit) {
        return jdbc.query("""
                SELECT * FROM pdei.audit_events WHERE merchant_id = :merchant
                 ORDER BY occurred_at, id LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("limit", limit <= 0 ? Integer.MAX_VALUE : limit), MAPPER);
    }

    @Override
    public List<AuditEvent> findByEntity(String entityType, String entityId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM pdei.audit_events
                 WHERE (:entityType IS NULL OR entity_type = :entityType)
                   AND (:entityId IS NULL OR entity_id = :entityId)
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("entityType", entityType)
                        .addValue("entityId", entityId)
                        .addValue("limit", Math.max(1, size))
                        .addValue("offset", JdbcSupport.offset(page, size)), MAPPER);
    }

    @Override
    public List<AuditEvent> findByFilter(String merchantId, String actor, Instant from, Instant to,
                                         int page, int size) {
        return jdbc.query("""
                SELECT * FROM pdei.audit_events
                 WHERE (:merchant IS NULL OR merchant_id = :merchant)
                   AND (:actor IS NULL OR actor = :actor)
                   AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                   AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("actor", actor)
                        .addValue("from", JdbcSupport.timestamp(from))
                        .addValue("to", JdbcSupport.timestamp(to))
                        .addValue("limit", Math.max(1, size))
                        .addValue("offset", JdbcSupport.offset(page, size)), MAPPER);
    }
}
