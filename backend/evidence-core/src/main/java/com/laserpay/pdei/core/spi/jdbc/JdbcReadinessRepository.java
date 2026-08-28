package com.laserpay.pdei.core.spi.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.spi.ReadinessRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter for {@code pdei.readiness_snapshots} and {@code pdei.readiness_gaps}.
 *
 * <p>Snapshots are append-only and store their requirement, gap and contradiction detail as JSON:
 * the snapshot is a point-in-time record of a computation, not a normalised entity to be queried
 * across. Gaps are additionally written to their own table because the at-risk feed
 * ({@code GET /api/v1/gaps}) queries across merchants by type and severity.</p>
 *
 * <p>Gap ids are deterministic, so the upsert makes recomputation idempotent: the same gap on the
 * same transaction updates in place instead of accumulating duplicates.</p>
 */
public class JdbcReadinessRepository implements ReadinessRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcReadinessRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReadinessRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReadinessGap> GAP = (rs, i) -> new ReadinessGap(
            rs.getString("id"),
            rs.getString("transaction_id"),
            JdbcSupport.enumValue(rs, "type", GapType.class),
            JdbcSupport.enumValue(rs, "evidence_type", EvidenceType.class),
            JdbcSupport.enumValue(rs, "severity", GapSeverity.class),
            rs.getString("evidence_id"),
            rs.getString("detail"),
            JdbcSupport.instant(rs, "detected_at"),
            JdbcSupport.instant(rs, "expires_at"));

    private final RowMapper<ReadinessSnapshot> snapshotMapper = (rs, i) -> new ReadinessSnapshot(
            rs.getString("id"),
            rs.getString("transaction_id"),
            rs.getString("merchant_id"),
            JdbcSupport.enumValue(rs, "reason_code", DisputeReasonCode.class),
            rs.getInt("score"),
            JdbcSupport.enumValue(rs, "band", ReadinessBand.class),
            rs.getDouble("base_score"),
            rs.getInt("penalty_points"),
            readList(rs.getString("requirements_json"), new TypeReference<List<RequirementView>>() { }),
            readList(rs.getString("gaps_json"), new TypeReference<List<ReadinessGap>>() { }),
            readList(rs.getString("contradictions_json"), new TypeReference<List<ContradictionView>>() { }),
            rs.getString("policy_version_id"),
            JdbcSupport.instant(rs, "computed_at"));

    private static <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(json, type);
        } catch (Exception e) {
            log.warn("could not deserialise stored readiness detail: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public void saveSnapshot(ReadinessSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO pdei.readiness_snapshots (id, transaction_id, merchant_id, reason_code, score,
                    band, base_score, penalty_points, policy_version_id, requirements_json, gaps_json,
                    contradictions_json, computed_at)
                VALUES (:id, :transactionId, :merchantId, :reasonCode, :score, :band, :baseScore,
                    :penaltyPoints, :policyVersionId, CAST(:requirements AS jsonb), CAST(:gaps AS jsonb),
                    CAST(:contradictions AS jsonb), :computedAt)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", snapshot.snapshotId())
                        .addValue("transactionId", snapshot.transactionId())
                        .addValue("merchantId", snapshot.merchantId())
                        .addValue("reasonCode", JdbcSupport.name(snapshot.reasonCode()))
                        .addValue("score", snapshot.score())
                        .addValue("band", JdbcSupport.name(snapshot.band()))
                        .addValue("baseScore", snapshot.baseScore())
                        .addValue("penaltyPoints", snapshot.penaltyPoints())
                        .addValue("policyVersionId", snapshot.policyVersionId())
                        .addValue("requirements", Json.write(snapshot.requirements()))
                        .addValue("gaps", Json.write(snapshot.gaps()))
                        .addValue("contradictions", Json.write(snapshot.contradictions()))
                        .addValue("computedAt", JdbcSupport.timestamp(snapshot.computedAt())));

        // Gaps that no longer exist are resolved rather than deleted: the at-risk feed shows what
        // changed, and an audit reader can see that a gap was closed rather than never recorded.
        List<String> currentIds = snapshot.gaps().stream().map(ReadinessGap::gapId).toList();
        jdbc.update("""
                UPDATE pdei.readiness_gaps SET resolved_at = :at
                 WHERE transaction_id = :tx AND resolved_at IS NULL
                   AND (:hasCurrent = FALSE OR id NOT IN (:ids))
                """,
                new MapSqlParameterSource()
                        .addValue("at", JdbcSupport.timestamp(snapshot.computedAt()))
                        .addValue("tx", snapshot.transactionId())
                        .addValue("hasCurrent", !currentIds.isEmpty())
                        .addValue("ids", currentIds.isEmpty() ? List.of("") : currentIds));

        List<MapSqlParameterSource> params = new ArrayList<>();
        for (ReadinessGap gap : snapshot.gaps()) {
            params.add(new MapSqlParameterSource()
                    .addValue("id", gap.gapId())
                    .addValue("transactionId", gap.transactionId())
                    .addValue("merchantId", snapshot.merchantId())
                    .addValue("type", JdbcSupport.name(gap.type()))
                    .addValue("evidenceType", JdbcSupport.name(gap.evidenceType()))
                    .addValue("severity", JdbcSupport.name(gap.severity()))
                    .addValue("evidenceId", gap.evidenceId())
                    .addValue("detail", gap.detail())
                    .addValue("detectedAt", JdbcSupport.timestamp(gap.detectedAt()))
                    .addValue("expiresAt", JdbcSupport.timestamp(gap.expiresAt())));
        }
        if (!params.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO pdei.readiness_gaps (id, transaction_id, merchant_id, type, evidence_type,
                        severity, evidence_id, detail, detected_at, expires_at, resolved_at)
                    VALUES (:id, :transactionId, :merchantId, :type, :evidenceType, :severity, :evidenceId,
                        :detail, :detectedAt, :expiresAt, NULL)
                    ON CONFLICT (id) DO UPDATE
                        SET severity = EXCLUDED.severity,
                            detail = EXCLUDED.detail,
                            expires_at = EXCLUDED.expires_at,
                            resolved_at = NULL
                    """, params.toArray(new MapSqlParameterSource[0]));
        }
    }

    @Override
    public Optional<ReadinessSnapshot> findLatest(String transactionId) {
        return jdbc.query("""
                        SELECT * FROM pdei.readiness_snapshots WHERE transaction_id = :tx
                         ORDER BY computed_at DESC LIMIT 1
                        """, Map.of("tx", transactionId), snapshotMapper)
                .stream().findFirst();
    }

    @Override
    public List<ReadinessSnapshot> findLatestForMerchant(String merchantId, int limit) {
        return jdbc.query("""
                SELECT DISTINCT ON (transaction_id) *
                  FROM pdei.readiness_snapshots
                 WHERE merchant_id = :merchant
                 ORDER BY transaction_id, computed_at DESC
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("limit", Math.max(1, limit)), snapshotMapper);
    }

    @Override
    public List<ReadinessGap> findGaps(String merchantId, GapType type, GapSeverity severity,
                                       int page, int size) {
        return jdbc.query("""
                SELECT * FROM pdei.readiness_gaps
                 WHERE resolved_at IS NULL
                   AND (CAST(:merchant AS text) IS NULL OR merchant_id = :merchant)
                   AND (CAST(:type AS text) IS NULL OR type = :type)
                   AND (CAST(:severity AS text) IS NULL OR severity = :severity)
                 ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2
                          ELSE 3 END, detected_at DESC
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("type", JdbcSupport.name(type))
                        .addValue("severity", JdbcSupport.name(severity))
                        .addValue("limit", Math.max(1, size))
                        .addValue("offset", JdbcSupport.offset(page, size)), GAP);
    }

    @Override
    public List<ReadinessGap> findGapsForTransaction(String transactionId) {
        return jdbc.query("""
                SELECT * FROM pdei.readiness_gaps
                 WHERE transaction_id = :tx AND resolved_at IS NULL
                 ORDER BY detected_at
                """, Map.of("tx", transactionId), GAP);
    }
}
