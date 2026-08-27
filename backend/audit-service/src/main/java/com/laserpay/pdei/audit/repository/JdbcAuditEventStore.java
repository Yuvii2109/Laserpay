package com.laserpay.pdei.audit.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link AuditEventStore} over {@code pdei.audit_events} as created by {@code V8__audit.sql}.
 *
 * <p>Three details of that migration shape this class:
 *
 * <ol>
 *   <li><strong>{@code sequence_no} is assigned by the database</strong>, never by the application.
 *       It is the chain order: two entries written in the same millisecond still have a total order,
 *       and verification walks that order rather than a timestamp that could tie.</li>
 *   <li><strong>The genesis link is stored as NULL.</strong> {@code ux_audit_events_genesis} is a
 *       unique index on {@code (merchant_id) WHERE previous_hash IS NULL}, so a merchant can have
 *       exactly one first entry. In Java, {@code AuditEvent} normalises a null predecessor to
 *       {@link Hashes#GENESIS_HASH} (sixty-four zeros) and hashes <em>that</em>, so this adapter
 *       translates in both directions: {@code GENESIS_HASH -> NULL} on write,
 *       {@code NULL -> GENESIS_HASH} on read. Getting this wrong in either direction would make
 *       every merchant's first entry fail verification.</li>
 *   <li><strong>Concurrency is settled by the database.</strong> {@code ux_audit_events_link} is
 *       unique on {@code (merchant_id, previous_hash)}, so two writers that both read the same head
 *       cannot both commit. The loser gets a constraint violation, which
 *       {@code AuditChainAppender} treats as "re-read the head and try again" rather than as an
 *       error.</li>
 * </ol>
 *
 * <p>This class contains no UPDATE and no DELETE. {@code V8} also installs
 * {@code trg_audit_events_immutable}, which rejects both at the database.
 */
public class JdbcAuditEventStore implements AuditEventStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditEventStore.class);

    private static final String COLUMNS = """
            audit_id, sequence_no, entity_type, entity_id, merchant_id, action, actor, actor_type,
            occurred_at, correlation_id, causation_id, source_event_id, before_state, after_state,
            previous_hash, hash
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuditEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    private static final RowMapper<AuditEvent> MAPPER = (rs, i) -> new AuditEvent(
            rs.getString("audit_id"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getString("merchant_id"),
            rs.getString("action"),
            rs.getString("actor"),
            enumValue(rs, "actor_type"),
            instant(rs, "occurred_at"),
            rs.getString("correlation_id"),
            tree(rs.getString("before_state")),
            tree(rs.getString("after_state")),
            // NULL in the column means "chain genesis"; AuditEvent hashes the canonical
            // GENESIS_HASH constant, so translate rather than pass the null through.
            fromStoredPreviousHash(rs.getString("previous_hash")),
            rs.getString("hash"));

    // --- append ---------------------------------------------------------------------------------

    /**
     * Insert one entry.
     *
     * <p>{@code ON CONFLICT (audit_id) DO NOTHING} makes redelivery of an entry this service has
     * already stored a no-op. It does <strong>not</strong> cover the chain-link conflict: that one
     * must surface, because it means the predecessor was taken and the entry has to be re-sealed
     * against the new head.
     */
    @Override
    public void append(AuditEvent event) {
        jdbc.update("""
                INSERT INTO pdei.audit_events (
                    audit_id, entity_type, entity_id, merchant_id, action, actor, actor_type,
                    occurred_at, correlation_id, before_state, after_state, previous_hash, hash)
                VALUES (
                    :auditId, :entityType, :entityId, :merchantId, :action, :actor, :actorType,
                    :occurredAt, :correlationId, CAST(:beforeState AS jsonb),
                    CAST(:afterState AS jsonb), :previousHash, :hash)
                ON CONFLICT (audit_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("auditId", event.auditId())
                        .addValue("entityType", event.entityType())
                        .addValue("entityId", event.entityId())
                        .addValue("merchantId", event.merchantId())
                        .addValue("action", event.action())
                        .addValue("actor", event.actor())
                        .addValue("actorType", event.actorType() == null
                                ? ActorType.SYSTEM.name() : event.actorType().name())
                        .addValue("occurredAt", timestamp(event.occurredAt()))
                        .addValue("correlationId", event.correlationId())
                        .addValue("beforeState", event.before() == null ? null : event.before().toString())
                        .addValue("afterState", event.after() == null ? null : event.after().toString())
                        .addValue("previousHash", toStoredPreviousHash(event.previousHash()))
                        .addValue("hash", event.hash()));
    }

    // --- chain reads ----------------------------------------------------------------------------

    /**
     * Hash of the newest entry in a merchant chain.
     *
     * <p>Ordered by {@code sequence_no}, the database-assigned chain position - not by
     * {@code occurred_at}, which two entries can share and which a producer controls.
     */
    @Override
    public Optional<String> lastHash(String merchantId) {
        List<String> hashes = jdbc.queryForList("""
                SELECT hash FROM pdei.audit_events
                 WHERE merchant_id = :merchantId
                 ORDER BY sequence_no DESC
                 LIMIT 1
                """, new MapSqlParameterSource("merchantId", merchantId), String.class);
        return hashes.stream().findFirst();
    }

    @Override
    public List<AuditEvent> findChain(String merchantId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM pdei.audit_events
                 WHERE merchant_id = :merchantId
                 ORDER BY sequence_no
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("merchantId", merchantId)
                        .addValue("limit", limit <= 0 ? Integer.MAX_VALUE : limit), MAPPER);
    }

    @Override
    public List<AuditEvent> findChainPage(String merchantId, long afterSequence, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM pdei.audit_events
                 WHERE merchant_id = :merchantId
                   AND sequence_no > :afterSequence
                 ORDER BY sequence_no
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("merchantId", merchantId)
                        .addValue("afterSequence", afterSequence)
                        .addValue("limit", Math.max(1, limit)), MAPPER);
    }

    @Override
    public long sequenceOf(String auditId) {
        List<Long> sequences = jdbc.queryForList(
                "SELECT sequence_no FROM pdei.audit_events WHERE audit_id = :auditId",
                new MapSqlParameterSource("auditId", auditId), Long.class);
        return sequences.isEmpty() || sequences.get(0) == null ? -1L : sequences.get(0);
    }

    @Override
    public boolean exists(String auditId) {
        if (auditId == null || auditId.isBlank()) {
            return false;
        }
        Boolean present = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pdei.audit_events WHERE audit_id = :auditId)",
                new MapSqlParameterSource("auditId", auditId), Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    @Override
    public long countChain(String merchantId) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM pdei.audit_events WHERE merchant_id = :merchantId",
                new MapSqlParameterSource("merchantId", merchantId), Long.class);
        return total == null ? 0L : total;
    }

    @Override
    public List<String> findChainKeys(int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT merchant_id FROM pdei.audit_events ORDER BY merchant_id LIMIT :limit
                """, new MapSqlParameterSource("limit", Math.max(1, limit)), String.class);
    }

    @Override
    public Instant[] chainBounds(String merchantId) {
        return jdbc.query("""
                SELECT min(occurred_at) AS oldest, max(occurred_at) AS newest
                  FROM pdei.audit_events WHERE merchant_id = :merchantId
                """, new MapSqlParameterSource("merchantId", merchantId),
                (RowMapper<Instant[]>) (rs, i) ->
                        new Instant[] {instant(rs, "oldest"), instant(rs, "newest")})
                .stream().findFirst().orElse(new Instant[] {null, null});
    }

    // --- filtered reads -------------------------------------------------------------------------

    @Override
    public List<AuditEvent> findByEntity(String entityType, String entityId, int page, int size) {
        return find(AuditQuery.forEntity(entityType, entityId, page, size));
    }

    @Override
    public List<AuditEvent> findByFilter(String merchantId, String actor, Instant from, Instant to,
                                         int page, int size) {
        return find(new AuditQuery(null, null, merchantId, actor, null, from, to, page, size));
    }

    @Override
    public List<AuditEvent> find(AuditQuery query) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.audit_events" + WHERE + """
                 ORDER BY occurred_at DESC, sequence_no DESC
                 LIMIT :limit OFFSET :offset
                """,
                params(query)
                        .addValue("limit", query.size())
                        .addValue("offset", query.offset()), MAPPER);
    }

    @Override
    public long count(AuditQuery query) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM pdei.audit_events" + WHERE, params(query), Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Keyset traversal by {@code sequence_no}.
     *
     * <p>Ascending and cursor-based, not {@code OFFSET}-based: an export of a million entries with
     * OFFSET degrades quadratically, and an entry appended mid-export would shift every subsequent
     * page. A sequence cursor is stable under concurrent appends - new entries simply appear at the
     * end, which for an append-only log is the correct behaviour.
     */
    @Override
    public long stream(AuditQuery query, int batchSize, long maxEvents, Consumer<AuditEvent> sink) {
        long emitted = 0L;
        long cursor = 0L;
        int limit = Math.max(1, batchSize);

        while (emitted < maxEvents) {
            List<AuditEvent> batch = jdbc.query("SELECT " + COLUMNS + " FROM pdei.audit_events"
                    + WHERE + """
                       AND sequence_no > :cursor
                     ORDER BY sequence_no
                     LIMIT :limit
                    """,
                    params(query)
                            .addValue("cursor", cursor)
                            .addValue("limit", limit), MAPPER);
            if (batch.isEmpty()) {
                break;
            }
            for (AuditEvent event : batch) {
                sink.accept(event);
                if (++emitted >= maxEvents) {
                    break;
                }
            }
            long lastSequence = sequenceOf(batch.get(batch.size() - 1).auditId());
            if (lastSequence <= cursor) {
                // Defensive: never loop forever if the cursor fails to advance.
                log.warn("audit export cursor did not advance past {}; stopping", cursor);
                break;
            }
            cursor = lastSequence;
            if (batch.size() < limit) {
                break;
            }
        }
        return emitted;
    }

    /**
     * Shared predicate.
     *
     * <p>Every optional parameter is wrapped in {@code CAST(:x AS ...)} before the null test:
     * PostgreSQL cannot infer the type of a bare placeholder in {@code ? IS NULL} and rejects the
     * statement outright. It is an easy line to write and a confusing one to debug.
     */
    private static final String WHERE = """
             WHERE (CAST(:entityType AS VARCHAR) IS NULL OR entity_type = :entityType)
               AND (CAST(:entityId AS VARCHAR) IS NULL OR entity_id = :entityId)
               AND (CAST(:merchantId AS VARCHAR) IS NULL OR merchant_id = :merchantId)
               AND (CAST(:actor AS VARCHAR) IS NULL OR actor = :actor)
               AND (CAST(:action AS VARCHAR) IS NULL OR action = :action)
               AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR occurred_at >= :from)
               AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR occurred_at < :to)
            """;

    private static MapSqlParameterSource params(AuditQuery query) {
        return new MapSqlParameterSource()
                .addValue("entityType", blankToNull(query.entityType()))
                .addValue("entityId", blankToNull(query.entityId()))
                .addValue("merchantId", blankToNull(query.merchantId()))
                .addValue("actor", blankToNull(query.actor()))
                .addValue("action", blankToNull(query.action()))
                .addValue("from", timestamp(query.from()))
                .addValue("to", timestamp(query.to()));
    }

    // --- conversions ----------------------------------------------------------------------------

    /** {@link Hashes#GENESIS_HASH} is the Java representation; NULL is the column representation. */
    static String toStoredPreviousHash(String previousHash) {
        if (previousHash == null || previousHash.isBlank()
                || Hashes.GENESIS_HASH.equals(previousHash)) {
            return null;
        }
        return previousHash;
    }

    static String fromStoredPreviousHash(String stored) {
        return stored == null || stored.isBlank() ? Hashes.GENESIS_HASH : stored;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static ActorType enumValue(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null || value.isBlank()) {
            return ActorType.SYSTEM;
        }
        try {
            return ActorType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return ActorType.SYSTEM;
        }
    }

    /**
     * Parse a stored JSONB column.
     *
     * <p>Returns null on unparseable content rather than throwing: a read of the audit log must
     * never be the thing that fails, and the row's hash still records exactly what was stored.
     */
    private static JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.readTree(json);
        } catch (RuntimeException e) {
            log.warn("unparseable audit payload column, returning null: {}", e.toString());
            return null;
        }
    }
}
