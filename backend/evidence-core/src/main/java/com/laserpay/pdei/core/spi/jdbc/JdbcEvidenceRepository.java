package com.laserpay.pdei.core.spi.jdbc;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.SearchPage;
import com.laserpay.pdei.core.spi.EvidenceRelationship;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.EvidenceVersionRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC adapter for {@code pdei.evidence}, {@code pdei.evidence_versions} and
 * {@code pdei.evidence_relationships}.
 *
 * <p>Plain SQL rather than JPA: these are read-mostly projections into immutable records, and there
 * is no object graph to manage. The column names assumed here are documented in the module
 * {@code context.md} - if the Flyway migrations in platform-persistence name them differently, this
 * is the only file that needs changing.</p>
 */
public class JdbcEvidenceRepository implements EvidenceRepositoryPort {

    private static final String COLUMNS = """
            id, merchant_id, transaction_id, type, status, source, object_key, sha256, version,
            filename, content_type, size_bytes, summary, source_event_id, parent_evidence_id,
            related_entity_id, quality_score, provenance_verified, created_at, observed_at, expires_at
            """;

    private static final RowMapper<EvidenceView> MAPPER = (rs, rowNum) -> new EvidenceView(
            rs.getString("id"),
            rs.getString("merchant_id"),
            rs.getString("transaction_id"),
            JdbcSupport.enumValue(rs, "type", EvidenceType.class),
            JdbcSupport.enumValue(rs, "status", EvidenceStatus.class),
            JdbcSupport.enumValue(rs, "source", EvidenceSource.class),
            rs.getString("object_key"),
            rs.getString("sha256"),
            rs.getInt("version"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("summary"),
            rs.getString("source_event_id"),
            rs.getString("parent_evidence_id"),
            rs.getString("related_entity_id"),
            rs.getDouble("quality_score"),
            rs.getBoolean("provenance_verified"),
            JdbcSupport.instant(rs, "created_at"),
            JdbcSupport.instant(rs, "observed_at"),
            JdbcSupport.instant(rs, "expires_at"));

    private static final RowMapper<EvidenceVersionRecord> VERSION_MAPPER = (rs, rowNum) ->
            new EvidenceVersionRecord(rs.getString("id"), rs.getString("evidence_id"), rs.getInt("version"),
                    rs.getString("object_key"), rs.getString("sha256"), rs.getLong("size_bytes"),
                    rs.getString("content_type"), rs.getString("filename"), rs.getString("source_event_id"),
                    rs.getString("created_by"), JdbcSupport.instant(rs, "created_at"));

    private static final RowMapper<EvidenceRelationship> RELATIONSHIP_MAPPER = (rs, rowNum) ->
            new EvidenceRelationship(rs.getString("id"), rs.getString("from_evidence_id"),
                    rs.getString("to_evidence_id"), rs.getString("relation"), rs.getString("detail"),
                    JdbcSupport.instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEvidenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EvidenceView> findById(String evidenceId) {
        List<EvidenceView> rows = jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE id = :id",
                Map.of("id", evidenceId), MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<EvidenceView> findByIds(Collection<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE id IN (:ids)",
                Map.of("ids", evidenceIds), MAPPER);
    }

    @Override
    public Set<String> existingIds(Collection<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return Set.of();
        }
        List<String> found = jdbc.queryForList("SELECT id FROM pdei.evidence WHERE id IN (:ids)",
                Map.of("ids", evidenceIds), String.class);
        return new LinkedHashSet<>(found);
    }

    @Override
    public List<EvidenceView> findByTransactionId(String transactionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE transaction_id = :tx"
                        + " ORDER BY created_at, id",
                Map.of("tx", transactionId), MAPPER);
    }

    @Override
    public List<EvidenceView> findByTransactionIdAndStatusIn(String transactionId,
                                                             Collection<EvidenceStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE transaction_id = :tx"
                        + " AND status IN (:statuses) ORDER BY created_at, id",
                Map.of("tx", transactionId, "statuses", statuses.stream().map(Enum::name).toList()), MAPPER);
    }

    @Override
    public List<EvidenceView> findByMerchantIdAndTypeAndStatus(String merchantId, EvidenceType type,
                                                               EvidenceStatus status) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE merchant_id = :merchant"
                        + " AND (:type IS NULL OR type = :type) AND (:status IS NULL OR status = :status)"
                        + " ORDER BY created_at DESC",
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("type", JdbcSupport.name(type))
                        .addValue("status", JdbcSupport.name(status)), MAPPER);
    }

    @Override
    public Optional<EvidenceView> findByShaAndTransactionId(String sha256, String transactionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE sha256 = :sha"
                                + " AND transaction_id = :tx ORDER BY version DESC LIMIT 1",
                        Map.of("sha", sha256, "tx", transactionId), MAPPER)
                .stream().findFirst();
    }

    @Override
    public List<EvidenceView> findExpiringBetween(Instant from, Instant to, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE expires_at IS NOT NULL"
                        + " AND expires_at >= :from AND expires_at < :to"
                        + " AND status NOT IN ('EXPIRED','INVALIDATED','SUPERSEDED')"
                        + " ORDER BY expires_at LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("from", JdbcSupport.timestamp(from))
                        .addValue("to", JdbcSupport.timestamp(to))
                        .addValue("limit", Math.max(1, limit)), MAPPER);
    }

    @Override
    public void insert(EvidenceView evidence) {
        jdbc.update("""
                INSERT INTO pdei.evidence (id, merchant_id, transaction_id, type, status, source,
                    object_key, sha256, version, filename, content_type, size_bytes, summary,
                    source_event_id, parent_evidence_id, related_entity_id, quality_score,
                    provenance_verified, created_at, observed_at, expires_at, updated_at)
                VALUES (:id, :merchantId, :transactionId, :type, :status, :source, :objectKey, :sha256,
                    :version, :filename, :contentType, :sizeBytes, :summary, :sourceEventId,
                    :parentEvidenceId, :relatedEntityId, :qualityScore, :provenanceVerified,
                    :createdAt, :observedAt, :expiresAt, :createdAt)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", evidence.evidenceId())
                        .addValue("merchantId", evidence.merchantId())
                        .addValue("transactionId", evidence.transactionId())
                        .addValue("type", JdbcSupport.name(evidence.type()))
                        .addValue("status", JdbcSupport.name(evidence.status()))
                        .addValue("source", JdbcSupport.name(evidence.source()))
                        .addValue("objectKey", evidence.objectKey())
                        .addValue("sha256", evidence.sha256())
                        .addValue("version", evidence.version())
                        .addValue("filename", evidence.filename())
                        .addValue("contentType", evidence.contentType())
                        .addValue("sizeBytes", evidence.sizeBytes())
                        .addValue("summary", evidence.summary())
                        .addValue("sourceEventId", evidence.sourceEventId())
                        .addValue("parentEvidenceId", evidence.parentEvidenceId())
                        .addValue("relatedEntityId", evidence.relatedEntityId())
                        .addValue("qualityScore", evidence.qualityScore())
                        .addValue("provenanceVerified", evidence.provenanceVerified())
                        .addValue("createdAt", JdbcSupport.timestamp(evidence.createdAt()))
                        .addValue("observedAt", JdbcSupport.timestamp(evidence.observedAt()))
                        .addValue("expiresAt", JdbcSupport.timestamp(evidence.expiresAt())));
    }

    @Override
    public boolean updateStatus(String evidenceId, EvidenceStatus status, Instant at, String reason) {
        return jdbc.update("""
                UPDATE pdei.evidence
                   SET status = :status, updated_at = :at, status_reason = :reason
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("status", JdbcSupport.name(status))
                        .addValue("at", JdbcSupport.timestamp(at))
                        .addValue("reason", reason)
                        .addValue("id", evidenceId)) > 0;
    }

    @Override
    public void updateSummaryAndQuality(String evidenceId, String summary, double qualityScore,
                                        boolean provenanceVerified) {
        jdbc.update("""
                UPDATE pdei.evidence
                   SET summary = :summary, quality_score = :quality, provenance_verified = :provenance
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("summary", summary)
                        .addValue("quality", qualityScore)
                        .addValue("provenance", provenanceVerified)
                        .addValue("id", evidenceId));
    }

    @Override
    public void insertVersion(EvidenceVersionRecord version) {
        jdbc.update("""
                INSERT INTO pdei.evidence_versions (id, evidence_id, version, object_key, sha256,
                    size_bytes, content_type, filename, source_event_id, created_by, created_at)
                VALUES (:id, :evidenceId, :version, :objectKey, :sha256, :sizeBytes, :contentType,
                    :filename, :sourceEventId, :createdBy, :createdAt)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", version.evidenceVersionId())
                        .addValue("evidenceId", version.evidenceId())
                        .addValue("version", version.version())
                        .addValue("objectKey", version.objectKey())
                        .addValue("sha256", version.sha256())
                        .addValue("sizeBytes", version.sizeBytes())
                        .addValue("contentType", version.contentType())
                        .addValue("filename", version.filename())
                        .addValue("sourceEventId", version.sourceEventId())
                        .addValue("createdBy", version.createdBy())
                        .addValue("createdAt", JdbcSupport.timestamp(version.createdAt())));
    }

    @Override
    public List<EvidenceVersionRecord> findVersions(String evidenceId) {
        return jdbc.query("SELECT * FROM pdei.evidence_versions WHERE evidence_id = :id ORDER BY version",
                Map.of("id", evidenceId), VERSION_MAPPER);
    }

    @Override
    public void insertRelationship(EvidenceRelationship relationship) {
        jdbc.update("""
                INSERT INTO pdei.evidence_relationships (id, from_evidence_id, to_evidence_id, relation,
                    detail, created_at)
                VALUES (:id, :from, :to, :relation, :detail, :createdAt)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", relationship.relationshipId())
                        .addValue("from", relationship.fromEvidenceId())
                        .addValue("to", relationship.toEvidenceId())
                        .addValue("relation", relationship.relation())
                        .addValue("detail", relationship.detail())
                        .addValue("createdAt", JdbcSupport.timestamp(relationship.createdAt())));
    }

    @Override
    public List<EvidenceRelationship> findRelationships(String evidenceId) {
        return jdbc.query("""
                SELECT * FROM pdei.evidence_relationships
                 WHERE from_evidence_id = :id OR to_evidence_id = :id
                 ORDER BY created_at
                """, Map.of("id", evidenceId), RELATIONSHIP_MAPPER);
    }

    @Override
    public List<EvidenceView> findChildren(String evidenceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE parent_evidence_id = :id"
                        + " ORDER BY version, id",
                Map.of("id", evidenceId), MAPPER);
    }

    @Override
    public SearchPage<EvidenceView> search(String tsQuery, String merchantId, EvidenceType type,
                                           EvidenceStatus status, String transactionId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", tsQuery == null || tsQuery.isBlank() ? null : tsQuery)
                .addValue("merchant", merchantId)
                .addValue("type", JdbcSupport.name(type))
                .addValue("status", JdbcSupport.name(status))
                .addValue("tx", transactionId)
                .addValue("limit", Math.max(1, size))
                .addValue("offset", JdbcSupport.offset(page, size));

        String where = """
                 WHERE (:merchant IS NULL OR merchant_id = :merchant)
                   AND (:type IS NULL OR type = :type)
                   AND (:status IS NULL OR status = :status)
                   AND (:tx IS NULL OR transaction_id = :tx)
                   AND (:q IS NULL OR search_vector @@ to_tsquery('english', :q))
                """;

        Long total = jdbc.queryForObject("SELECT count(*) FROM pdei.evidence" + where, params, Long.class);
        List<EvidenceView> items = jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence" + where
                        + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
                params, MAPPER);
        return new SearchPage<>(items, Math.max(0, page), Math.max(1, size), total == null ? 0L : total);
    }

    @Override
    public long countByMerchantAndStatus(String merchantId, EvidenceStatus status) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM pdei.evidence
                 WHERE (:merchant IS NULL OR merchant_id = :merchant)
                   AND (:status IS NULL OR status = :status)
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("status", JdbcSupport.name(status)), Long.class);
        return count == null ? 0L : count;
    }
}
