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
 * is no object graph to manage.</p>
 *
 * <p><strong>The Flyway migrations in platform-persistence are the authority on column names, not
 * this file and not any prose.</strong> An earlier version of this javadoc said the names were
 * "documented in the module context.md", and they were - documented, and wrong. Nine of them did
 * not exist. Every read and write of {@code pdei.evidence} failed at runtime with
 * {@code column "id" does not exist}, so the platform held no evidence at all, and no unit test
 * noticed because none of them touch a database. If you change a column here, change it in a
 * migration first and re-read the DDL, not the docs.</p>
 */
public class JdbcEvidenceRepository implements EvidenceRepositoryPort {

    // Aliased, not renamed, so the RowMapper below and every ORDER BY can keep using the record's
    // vocabulary while the SQL uses the schema's. The three names that differ are not typos:
    //
    //   evidence_id      the primary key. V3 names every key after its table (transaction_id,
    //                    relationship_id, ...); there is no bare "id" column anywhere in pdei.
    //   current_version  the evidence version NUMBER. A column called "version" does exist on
    //                    this table and is the JPA optimistic-lock counter - selecting it would
    //                    have compiled, run, and quietly returned the wrong integer, which is a
    //                    worse failure than the one this file actually had.
    //   status_reason    added by V11; see that migration for why invalidated_reason is not it.
    private static final String COLUMNS = """
            evidence_id AS id, merchant_id, transaction_id, type, status, source, object_key, sha256,
            current_version AS version, filename, content_type, size_bytes, summary, source_event_id,
            parent_evidence_id, related_entity_id, quality_score, provenance_verified,
            created_at, observed_at, expires_at
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

    // pdei.evidence_versions: the key is evidence_version_id and the ordinal is version_number.
    private static final String VERSION_COLUMNS = """
            evidence_version_id AS id, evidence_id, version_number AS version, object_key, sha256,
            size_bytes, content_type, filename, source_event_id, created_by, created_at
            """;

    private static final RowMapper<EvidenceVersionRecord> VERSION_MAPPER = (rs, rowNum) ->
            new EvidenceVersionRecord(rs.getString("id"), rs.getString("evidence_id"), rs.getInt("version"),
                    rs.getString("object_key"), rs.getString("sha256"), rs.getLong("size_bytes"),
                    rs.getString("content_type"), rs.getString("filename"), rs.getString("source_event_id"),
                    rs.getString("created_by"), JdbcSupport.instant(rs, "created_at"));

    // pdei.evidence_relationships: the key is relationship_id and the edge label is
    // relationship_type. Both differ from what this file assumed.
    private static final String RELATIONSHIP_COLUMNS = """
            relationship_id AS id, from_evidence_id, to_evidence_id,
            relationship_type AS relation, detail, created_at
            """;

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
        List<EvidenceView> rows = jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE evidence_id = :id",
                Map.of("id", evidenceId), MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public List<EvidenceView> findByIds(Collection<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE evidence_id IN (:ids)",
                Map.of("ids", evidenceIds), MAPPER);
    }

    @Override
    public Set<String> existingIds(Collection<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return Set.of();
        }
        List<String> found = jdbc.queryForList("SELECT evidence_id FROM pdei.evidence WHERE evidence_id IN (:ids)",
                Map.of("ids", evidenceIds), String.class);
        return new LinkedHashSet<>(found);
    }

    @Override
    public List<EvidenceView> findByTransactionId(String transactionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE transaction_id = :tx"
                        + " ORDER BY created_at, evidence_id",
                Map.of("tx", transactionId), MAPPER);
    }

    @Override
    public List<EvidenceView> findByTransactionIdAndStatusIn(String transactionId,
                                                             Collection<EvidenceStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE transaction_id = :tx"
                        + " AND status IN (:statuses) ORDER BY created_at, evidence_id",
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
                                + " AND transaction_id = :tx ORDER BY current_version DESC LIMIT 1",
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
                INSERT INTO pdei.evidence (evidence_id, merchant_id, transaction_id, type, status, source,
                    object_key, sha256, current_version, filename, content_type, size_bytes, summary,
                    source_event_id, parent_evidence_id, related_entity_id, quality_score,
                    provenance_verified, created_at, observed_at, expires_at, updated_at)
                VALUES (:id, :merchantId, :transactionId, :type, :status, :source, :objectKey, :sha256,
                    :version, :filename, :contentType, :sizeBytes, :summary, :sourceEventId,
                    :parentEvidenceId, :relatedEntityId, :qualityScore, :provenanceVerified,
                    :createdAt, :observedAt, :expiresAt, :createdAt)
                ON CONFLICT (evidence_id) DO NOTHING
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

    /**
     * {@code status_reason} records the reason for whichever transition just happened - this is
     * called for SUPERSEDED, EXPIRED, EXPIRING and INVALIDATED alike. The INVALIDATED case
     * additionally stamps V3's {@code invalidated_at} / {@code invalidated_reason}, which are
     * narrower by design and are what {@code EvidenceEntity} maps; the CASE expressions leave
     * them untouched for every other status rather than overwriting a past invalidation.
     */
    @Override
    public boolean updateStatus(String evidenceId, EvidenceStatus status, Instant at, String reason) {
        return jdbc.update("""
                UPDATE pdei.evidence
                   SET status = :status,
                       updated_at = :at,
                       status_reason = :reason,
                       invalidated_at = CASE WHEN :status = 'INVALIDATED'
                                             THEN :at ELSE invalidated_at END,
                       invalidated_reason = CASE WHEN :status = 'INVALIDATED'
                                                 THEN :reason ELSE invalidated_reason END
                 WHERE evidence_id = :id
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
                 WHERE evidence_id = :id
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
                INSERT INTO pdei.evidence_versions (evidence_version_id, evidence_id, version_number, object_key, sha256,
                    size_bytes, content_type, filename, source_event_id, created_by, created_at)
                VALUES (:id, :evidenceId, :version, :objectKey, :sha256, :sizeBytes, :contentType,
                    :filename, :sourceEventId, :createdBy, :createdAt)
                ON CONFLICT (evidence_version_id) DO NOTHING
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
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM pdei.evidence_versions"
                        + " WHERE evidence_id = :id ORDER BY version_number",
                Map.of("id", evidenceId), VERSION_MAPPER);
    }

    @Override
    public void insertRelationship(EvidenceRelationship relationship) {
        jdbc.update("""
                INSERT INTO pdei.evidence_relationships (relationship_id, from_evidence_id, to_evidence_id,
                    relationship_type, detail, created_at)
                VALUES (:id, :from, :to, :relation, :detail, :createdAt)
                ON CONFLICT (relationship_id) DO NOTHING
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
        return jdbc.query("SELECT " + RELATIONSHIP_COLUMNS + " FROM pdei.evidence_relationships"
                        + " WHERE from_evidence_id = :id OR to_evidence_id = :id"
                        + " ORDER BY created_at",
                Map.of("id", evidenceId), RELATIONSHIP_MAPPER);
    }

    @Override
    public List<EvidenceView> findChildren(String evidenceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pdei.evidence WHERE parent_evidence_id = :id"
                        + " ORDER BY current_version, evidence_id",
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
                        + " ORDER BY created_at DESC, evidence_id LIMIT :limit OFFSET :offset",
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
