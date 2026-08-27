package com.laserpay.pdei.readiness.persistence;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * {@link EvidenceExpiryStore} over {@code pdei.evidence} as created by {@code V3__evidence.sql}.
 *
 * <p>Both queries are served by {@code ix_evidence_expires_at}, the partial index on
 * {@code expires_at WHERE expires_at IS NOT NULL} - so the nightly sweep never scans the evidence
 * table, however large it grows.
 *
 * <p>The transition statement carries its own guard ({@code AND status IN (:from)}), so correctness
 * does not depend on the caller having read a fresh row: two workers issuing the same UPDATE
 * concurrently produce one winner and one zero-row result, decided by Postgres rather than by
 * application timing.
 */
public class JdbcEvidenceExpiryStore implements EvidenceExpiryStore {

    private static final RowMapper<ExpiringEvidence> MAPPER = (rs, i) -> new ExpiringEvidence(
            rs.getString("evidence_id"),
            rs.getString("merchant_id"),
            rs.getString("transaction_id"),
            Sql.enumValue(rs, "type", EvidenceType.class),
            Sql.enumValue(rs, "status", EvidenceStatus.class),
            rs.getString("source_event_id"),
            Sql.instant(rs, "expires_at"));

    private static final String COLUMNS =
            "evidence_id, merchant_id, transaction_id, type, status, source_event_id, expires_at";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEvidenceExpiryStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public List<ExpiringEvidence> findDueForExpiry(Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM pdei.evidence
                 WHERE expires_at IS NOT NULL
                   AND expires_at <= :now
                   AND status IN (:statuses)
                 ORDER BY expires_at
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("now", Sql.timestamp(now))
                        .addValue("statuses", Sql.names(EXPIRABLE))
                        .addValue("limit", Math.max(1, limit)), MAPPER);
    }

    @Override
    public List<ExpiringEvidence> findEnteringWarningWindow(Instant now, Instant windowEnd, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM pdei.evidence
                 WHERE expires_at IS NOT NULL
                   AND expires_at > :now
                   AND expires_at <= :windowEnd
                   AND status = :active
                 ORDER BY expires_at
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("now", Sql.timestamp(now))
                        .addValue("windowEnd", Sql.timestamp(windowEnd))
                        .addValue("active", EvidenceStatus.ACTIVE.name())
                        .addValue("limit", Math.max(1, limit)), MAPPER);
    }

    @Override
    @Transactional
    public boolean transition(String evidenceId, Collection<EvidenceStatus> from, EvidenceStatus to,
                              Instant at) {
        int updated = jdbc.update("""
                UPDATE pdei.evidence
                   SET status = :to, updated_at = :at
                 WHERE evidence_id = :evidenceId
                   AND status IN (:from)
                """,
                new MapSqlParameterSource()
                        .addValue("to", to.name())
                        .addValue("at", Sql.timestamp(at))
                        .addValue("evidenceId", evidenceId)
                        .addValue("from", Sql.names(List.copyOf(from))));
        return updated > 0;
    }

    @Override
    public EvidenceStatus statusOf(String evidenceId) {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM pdei.evidence WHERE evidence_id = :evidenceId",
                new MapSqlParameterSource("evidenceId", evidenceId), String.class);
        if (statuses.isEmpty()) {
            return null;
        }
        try {
            return EvidenceStatus.valueOf(statuses.get(0));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
