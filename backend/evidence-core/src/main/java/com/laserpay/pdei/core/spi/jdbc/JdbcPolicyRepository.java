package com.laserpay.pdei.core.spi.jdbc;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.policy.RequirementSpec;
import com.laserpay.pdei.core.spi.PolicyRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter for {@code pdei.policies}, {@code pdei.policy_versions} and
 * {@code pdei.evidence_requirements}.
 *
 * <p>Versions are append-only. {@code closePreviousVersion} only ever sets {@code effective_to}; it
 * never touches the rules of a stored version, so a decision made last month can be replayed against
 * exactly the policy that was in force then.</p>
 */
public class JdbcPolicyRepository implements PolicyRepositoryPort {

    private static final String VERSION_COLUMNS = """
            id, policy_id, version, merchant_id, reason_code, permitted_actions,
            prohibited_evidence_types, auto_prepare_min_confidence, max_contradictions,
            min_readiness_score, human_review_above_amount_minor, currency, auto_submit_enabled,
            response_window_days, expiring_soon_days, created_by, checksum, effective_from, effective_to
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<PolicyView> mapper = (rs, rowNum) -> new PolicyView(
            rs.getString("policy_id"),
            rs.getString("id"),
            rs.getInt("version"),
            rs.getString("merchant_id"),
            JdbcSupport.enumValue(rs, "reason_code", DisputeReasonCode.class),
            findRequirements(rs.getString("id")),
            JdbcSupport.enumSet(rs.getString("permitted_actions"), RecommendedAction.class),
            JdbcSupport.enumSet(rs.getString("prohibited_evidence_types"), EvidenceType.class),
            rs.getDouble("auto_prepare_min_confidence"),
            rs.getInt("max_contradictions"),
            rs.getInt("min_readiness_score"),
            rs.getLong("human_review_above_amount_minor"),
            rs.getString("currency"),
            rs.getBoolean("auto_submit_enabled"),
            rs.getInt("response_window_days"),
            rs.getInt("expiring_soon_days"),
            rs.getString("created_by"),
            rs.getString("checksum"),
            JdbcSupport.instant(rs, "effective_from"),
            JdbcSupport.instant(rs, "effective_to"),
            false);

    private static final RowMapper<RequirementSpec> REQUIREMENT = (rs, i) -> new RequirementSpec(
            JdbcSupport.enumValue(rs, "evidence_type", EvidenceType.class),
            JdbcSupport.enumValue(rs, "strength", RequirementStrength.class),
            rs.getInt("weight"),
            rs.getObject("max_age_days") == null ? null : rs.getInt("max_age_days"),
            rs.getBoolean("provenance_required"),
            rs.getDouble("min_quality_score"),
            rs.getString("note"));

    private List<RequirementSpec> findRequirements(String policyVersionId) {
        return jdbc.query("""
                SELECT * FROM pdei.evidence_requirements
                 WHERE policy_version_id = :id ORDER BY strength, evidence_type
                """, Map.of("id", policyVersionId), REQUIREMENT);
    }

    @Override
    public Optional<PolicyView> findActive(String merchantId, DisputeReasonCode reasonCode, Instant at) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM pdei.policy_versions"
                                + " WHERE merchant_id = :merchant"
                                + "   AND ((:reason IS NULL AND reason_code IS NULL)"
                                + "        OR reason_code = :reason)"
                                + "   AND effective_from <= :at"
                                + "   AND (effective_to IS NULL OR effective_to > :at)"
                                + " ORDER BY version DESC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("reason", JdbcSupport.name(reasonCode))
                                .addValue("at", JdbcSupport.timestamp(at == null ? Instant.now() : at)),
                        mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<PolicyView> findByPolicyId(String policyId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM pdei.policy_versions"
                                + " WHERE policy_id = :id ORDER BY version DESC LIMIT 1",
                        Map.of("id", policyId), mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<PolicyView> findByVersionId(String policyVersionId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM pdei.policy_versions WHERE id = :id",
                        Map.of("id", policyVersionId), mapper)
                .stream().findFirst();
    }

    @Override
    public List<PolicyView> findHistory(String policyId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM pdei.policy_versions"
                        + " WHERE policy_id = :id ORDER BY version DESC", Map.of("id", policyId), mapper);
    }

    @Override
    public List<PolicyView> findByMerchant(String merchantId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM pdei.policy_versions"
                        + " WHERE merchant_id = :merchant AND effective_to IS NULL"
                        + " ORDER BY reason_code NULLS FIRST, version DESC",
                Map.of("merchant", merchantId), mapper);
    }

    @Override
    public void insertVersion(PolicyView version) {
        jdbc.update("""
                INSERT INTO pdei.policies (id, merchant_id, reason_code, current_version_id, created_at,
                    updated_at)
                VALUES (:policyId, :merchantId, :reasonCode, :versionId, :effectiveFrom, :effectiveFrom)
                ON CONFLICT (id) DO UPDATE
                    SET current_version_id = EXCLUDED.current_version_id,
                        updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("policyId", version.policyId())
                        .addValue("merchantId", version.merchantId())
                        .addValue("reasonCode", JdbcSupport.name(version.reasonCode()))
                        .addValue("versionId", version.policyVersionId())
                        .addValue("effectiveFrom", JdbcSupport.timestamp(version.effectiveFrom())));

        jdbc.update("""
                INSERT INTO pdei.policy_versions (id, policy_id, version, merchant_id, reason_code,
                    permitted_actions, prohibited_evidence_types, auto_prepare_min_confidence,
                    max_contradictions, min_readiness_score, human_review_above_amount_minor, currency,
                    auto_submit_enabled, response_window_days, expiring_soon_days, created_by, checksum,
                    effective_from, effective_to)
                VALUES (:id, :policyId, :version, :merchantId, :reasonCode, :permittedActions,
                    :prohibitedTypes, :minConfidence, :maxContradictions, :minReadiness,
                    :humanReviewAbove, :currency, :autoSubmit, :responseWindow, :expiringSoon,
                    :createdBy, :checksum, :effectiveFrom, NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", version.policyVersionId())
                        .addValue("policyId", version.policyId())
                        .addValue("version", version.version())
                        .addValue("merchantId", version.merchantId())
                        .addValue("reasonCode", JdbcSupport.name(version.reasonCode()))
                        .addValue("permittedActions", JdbcSupport.csv(version.permittedActions()))
                        .addValue("prohibitedTypes", JdbcSupport.csv(version.prohibitedEvidenceTypes()))
                        .addValue("minConfidence", version.autoPrepareMinConfidence())
                        .addValue("maxContradictions", version.maxContradictions())
                        .addValue("minReadiness", version.minReadinessScoreForAutoPrepare())
                        .addValue("humanReviewAbove", version.humanReviewAboveAmountMinor())
                        .addValue("currency", version.currency())
                        .addValue("autoSubmit", version.autoSubmitEnabled())
                        .addValue("responseWindow", version.responseWindowDays())
                        .addValue("expiringSoon", version.expiringSoonDays())
                        .addValue("createdBy", version.createdBy())
                        .addValue("checksum", version.checksum())
                        .addValue("effectiveFrom", JdbcSupport.timestamp(version.effectiveFrom())));

        List<MapSqlParameterSource> requirementParams = new ArrayList<>();
        for (RequirementSpec spec : version.requirements()) {
            requirementParams.add(new MapSqlParameterSource()
                    .addValue("id", version.policyVersionId() + ":" + spec.type())
                    .addValue("policyVersionId", version.policyVersionId())
                    .addValue("evidenceType", JdbcSupport.name(spec.type()))
                    .addValue("strength", JdbcSupport.name(spec.strength()))
                    .addValue("weight", spec.effectiveWeight())
                    .addValue("maxAgeDays", spec.maxAgeDays())
                    .addValue("provenanceRequired", spec.provenanceRequired())
                    .addValue("minQualityScore", spec.minQualityScore())
                    .addValue("note", spec.note()));
        }
        if (!requirementParams.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO pdei.evidence_requirements (id, policy_version_id, evidence_type, strength,
                        weight, max_age_days, provenance_required, min_quality_score, note)
                    VALUES (:id, :policyVersionId, :evidenceType, :strength, :weight, :maxAgeDays,
                        :provenanceRequired, :minQualityScore, :note)
                    ON CONFLICT (id) DO NOTHING
                    """, requirementParams.toArray(new MapSqlParameterSource[0]));
        }
    }

    @Override
    public void closePreviousVersion(String policyId, String previousVersionId, Instant effectiveTo) {
        jdbc.update("""
                UPDATE pdei.policy_versions SET effective_to = :effectiveTo
                 WHERE id = :id AND policy_id = :policyId AND effective_to IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("effectiveTo", JdbcSupport.timestamp(effectiveTo))
                        .addValue("id", previousVersionId)
                        .addValue("policyId", policyId));
    }

    @Override
    public List<DisputeReasonCode> topReasonCodes(String merchantId, int limit) {
        List<String> codes = jdbc.queryForList("""
                SELECT reason_code FROM pdei.disputes
                 WHERE merchant_id = :merchant AND reason_code IS NOT NULL
                 GROUP BY reason_code
                 ORDER BY count(*) DESC
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("limit", Math.max(1, limit)), String.class);
        List<DisputeReasonCode> result = new ArrayList<>();
        for (String code : codes) {
            try {
                result.add(DisputeReasonCode.valueOf(code));
            } catch (IllegalArgumentException ignored) {
                // skip codes this build does not know
            }
        }
        return List.copyOf(result);
    }
}
