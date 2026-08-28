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

    /**
     * A policy version, assembled from the two tables that actually hold it.
     *
     * <p>{@code merchant_id} and {@code reason_code} live on {@code policies}, not on
     * {@code policy_versions}, so every read joins. Six knobs on {@link PolicyView} -
     * {@code min_readiness_score}, {@code human_review_above_amount_minor}, {@code currency},
     * {@code auto_submit_enabled}, {@code response_window_days}, {@code expiring_soon_days} - have
     * no column in any table and are supplied here as literals until the schema grows them.
     * {@code expiring_soon_days} is 7 because contract §7 defines the EXPIRING_SOON window as 7
     * days; the rest default to the safe end (no auto-submit, no readiness floor, no amount
     * threshold) so a missing column can never widen what the policy permits.
     *
     * <p>Confidence is stored in basis points and converted to the 0.0-1.0 fraction
     * {@code PolicyConstraints} exposes; the AI safety gate (contract §9.3 rule 4) compares
     * against that fraction, so the conversion belongs here at the edge and nowhere else.
     */
    private static final String VERSION_FROM = """
             FROM pdei.policy_versions pv
             JOIN pdei.policies p ON p.policy_id = pv.policy_id
            """;

    private static final String VERSION_COLUMNS = """
            pv.policy_version_id AS id, pv.policy_id, pv.version_number AS version,
            p.merchant_id, p.reason_code,
            pv.permitted_actions, pv.prohibited_evidence_types,
            pv.auto_prepare_min_confidence_bps, pv.max_contradictions,
            0            AS min_readiness_score,
            0::bigint    AS human_review_above_amount_minor,
            NULL::text   AS currency,
            FALSE        AS auto_submit_enabled,
            0            AS response_window_days,
            7            AS expiring_soon_days,
            pv.created_by, pv.sha256 AS checksum, pv.effective_from, pv.effective_to
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
            findRequirements(rs.getString("policy_id"), rs.getInt("version")),
            JdbcSupport.enumSet(rs.getString("permitted_actions"), RecommendedAction.class),
            JdbcSupport.enumSet(rs.getString("prohibited_evidence_types"), EvidenceType.class),
            rs.getInt("auto_prepare_min_confidence_bps") / 10000.0d,
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

    // evidence_requirements has no provenance_required or min_quality_score column, and its free
    // text is `description`, not `note`. The two absent flags default to "not required" / "no
    // floor" so a missing column cannot silently raise a requirement the stored policy never set.
    private static final RowMapper<RequirementSpec> REQUIREMENT = (rs, i) -> new RequirementSpec(
            JdbcSupport.enumValue(rs, "evidence_type", EvidenceType.class),
            JdbcSupport.enumValue(rs, "strength", RequirementStrength.class),
            rs.getInt("weight"),
            rs.getObject("max_age_days") == null ? null : rs.getInt("max_age_days"),
            false,
            0.0d,
            rs.getString("description"));

    /** Requirements are keyed by (policy_id, policy_version), where policy_version is the number. */
    private List<RequirementSpec> findRequirements(String policyId, int versionNumber) {
        return jdbc.query("""
                SELECT evidence_type, strength, weight, max_age_days, description
                  FROM pdei.evidence_requirements
                 WHERE policy_id = :id AND policy_version = :version
                 ORDER BY strength, evidence_type
                """,
                new MapSqlParameterSource().addValue("id", policyId).addValue("version", versionNumber),
                REQUIREMENT);
    }

    @Override
    public Optional<PolicyView> findActive(String merchantId, DisputeReasonCode reasonCode, Instant at) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + VERSION_FROM
                                + " WHERE p.merchant_id = :merchant"
                                + "   AND ((CAST(:reason AS text) IS NULL AND p.reason_code IS NULL)"
                                + "        OR p.reason_code = :reason)"
                                + "   AND pv.effective_from <= :at"
                                + "   AND (pv.effective_to IS NULL OR pv.effective_to > :at)"
                                + " ORDER BY pv.version_number DESC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("reason", JdbcSupport.name(reasonCode))
                                .addValue("at", JdbcSupport.timestamp(at == null ? Instant.now() : at)),
                        mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<PolicyView> findByPolicyId(String policyId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + VERSION_FROM
                                + " WHERE pv.policy_id = :id ORDER BY pv.version_number DESC LIMIT 1",
                        Map.of("id", policyId), mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<PolicyView> findByVersionId(String policyVersionId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + VERSION_FROM + " WHERE pv.policy_version_id = :id",
                        Map.of("id", policyVersionId), mapper)
                .stream().findFirst();
    }

    @Override
    public List<PolicyView> findHistory(String policyId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + VERSION_FROM
                        + " WHERE pv.policy_id = :id ORDER BY pv.version_number DESC", Map.of("id", policyId), mapper);
    }

    @Override
    public List<PolicyView> findByMerchant(String merchantId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + VERSION_FROM
                        + " WHERE p.merchant_id = :merchant AND pv.effective_to IS NULL"
                        + " ORDER BY p.reason_code NULLS FIRST, pv.version_number DESC",
                Map.of("merchant", merchantId), mapper);
    }

    @Override
    public void insertVersion(PolicyView version) {
        jdbc.update("""
                INSERT INTO pdei.policies (policy_id, merchant_id, reason_code, current_version,
                    created_at, updated_at)
                VALUES (:policyId, :merchantId, :reasonCode, :version, :effectiveFrom, :effectiveFrom)
                ON CONFLICT (policy_id) DO UPDATE
                    SET current_version = EXCLUDED.current_version,
                        updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("policyId", version.policyId())
                        .addValue("merchantId", version.merchantId())
                        .addValue("reasonCode", JdbcSupport.name(version.reasonCode()))
                        .addValue("version", version.version())
                        .addValue("effectiveFrom", JdbcSupport.timestamp(version.effectiveFrom())));

        jdbc.update("""
                INSERT INTO pdei.policy_versions (policy_version_id, policy_id, version_number,
                    permitted_actions, prohibited_evidence_types, auto_prepare_min_confidence_bps,
                    max_contradictions, created_by, sha256, effective_from, effective_to)
                VALUES (:id, :policyId, :version, :permittedActions, :prohibitedTypes,
                    :minConfidenceBps, :maxContradictions, :createdBy, :checksum, :effectiveFrom, NULL)
                ON CONFLICT (policy_version_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", version.policyVersionId())
                        .addValue("policyId", version.policyId())
                        .addValue("version", version.version())
                        .addValue("merchantId", version.merchantId())
                        .addValue("reasonCode", JdbcSupport.name(version.reasonCode()))
                        .addValue("permittedActions", JdbcSupport.csv(version.permittedActions()))
                        .addValue("prohibitedTypes", JdbcSupport.csv(version.prohibitedEvidenceTypes()))
                        .addValue("minConfidenceBps", (int) Math.round(version.autoPrepareMinConfidence() * 10000))
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
                    .addValue("policyId", version.policyId())
                    .addValue("policyVersion", version.version())
                    .addValue("merchantId", version.merchantId())
                    .addValue("reasonCode", JdbcSupport.name(version.reasonCode()))
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
                    INSERT INTO pdei.evidence_requirements (requirement_id, policy_id, policy_version,
                        merchant_id, reason_code, evidence_type, strength, weight, max_age_days,
                        description)
                    VALUES (:id, :policyId, :policyVersion, :merchantId, :reasonCode, :evidenceType,
                        :strength, :weight, :maxAgeDays, :note)
                    ON CONFLICT (requirement_id) DO NOTHING
                    """, requirementParams.toArray(new MapSqlParameterSource[0]));
        }
    }

    @Override
    public void closePreviousVersion(String policyId, String previousVersionId, Instant effectiveTo) {
        jdbc.update("""
                UPDATE pdei.policy_versions SET effective_to = :effectiveTo
                 WHERE policy_version_id = :id AND policy_id = :policyId AND effective_to IS NULL
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
