package com.laserpay.pdei.readiness.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.core.spi.ReadinessRepositoryPort;
import com.laserpay.pdei.readiness.recompute.RecomputeTrigger;
import com.laserpay.pdei.readiness.sweep.AtRiskEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for {@code pdei.readiness_snapshots} and {@code pdei.readiness_gaps}, written
 * against the columns actually created by {@code V6__readiness.sql}.
 *
 * <p>This class implements {@link ReadinessRepositoryPort} and is registered as a bean by
 * {@code ReadinessWorkerConfig}, which makes {@code CorePersistenceAutoConfiguration} back off
 * (every port bean there is {@code @ConditionalOnMissingBean}). Two reasons it exists rather than
 * reusing the shared adapter:
 *
 * <ol>
 *   <li>the shared adapter cannot express what the worker must record - {@code is_current},
 *       {@code trigger_reason} and {@code trigger_event_id} have no place in
 *       {@link ReadinessRepositoryPort#saveSnapshot};</li>
 *   <li>at the time of writing the shared adapter's column names diverge from the migration (see
 *       "Known gaps" in this module's {@code context.md}), so relying on it would make the worker
 *       fail at runtime rather than at review time.</li>
 * </ol>
 *
 * <p><strong>Snapshots are append-only.</strong> A recomputation inserts a new row and clears
 * {@code is_current} on the previous one for the same (transaction, reasonCode) pair, so the score
 * history of a transaction is fully reconstructable - which is the whole point of a snapshot table.
 *
 * <p><strong>Gaps are upserted, never deleted.</strong> Gap ids are deterministic (the same gap on
 * the same transaction always hashes to the same id), so a recomputation updates in place. Gaps
 * that no longer exist are marked {@code resolved}, not removed: the at-risk feed must be able to
 * show that something was fixed, and an auditor must be able to see that it was ever there.
 */
public class ReadinessStore implements ReadinessRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(ReadinessStore.class);

    private final NamedParameterJdbcTemplate jdbc;

    public ReadinessStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    // --- row mappers ----------------------------------------------------------------------------

    private static final RowMapper<ReadinessGap> GAP_MAPPER = (rs, i) -> new ReadinessGap(
            rs.getString("gap_id"),
            rs.getString("transaction_id"),
            Sql.enumValue(rs, "type", GapType.class),
            Sql.enumValue(rs, "evidence_type", EvidenceType.class),
            Sql.enumValue(rs, "severity", GapSeverity.class),
            rs.getString("evidence_id"),
            rs.getString("detail"),
            Sql.instant(rs, "detected_at"),
            expiresAtFromMetadata(rs.getString("metadata")));

    private final RowMapper<ReadinessSnapshot> snapshotMapper = (rs, i) -> {
        String transactionId = rs.getString("transaction_id");
        List<ReadinessGap> gaps = findGapsForTransaction(transactionId);
        return new ReadinessSnapshot(
                rs.getString("snapshot_id"),
                transactionId,
                rs.getString("merchant_id"),
                Sql.enumValue(rs, "reason_code", DisputeReasonCode.class),
                rs.getInt("score"),
                Sql.enumValue(rs, "band", ReadinessBand.class),
                rs.getInt("base_score"),
                rs.getInt("penalty_total"),
                readList(rs.getString("requirements"), new TypeReference<List<RequirementView>>() { }),
                gaps,
                contradictionsFrom(gaps),
                policyVersionId(rs.getString("policy_id"), Sql.integer(rs, "policy_version")),
                Sql.instant(rs, "computed_at"));
    };

    private static final RowMapper<AtRiskEntry> AT_RISK_MAPPER = (rs, i) -> new AtRiskEntry(
            rs.getString("transaction_id"),
            rs.getString("merchant_id"),
            rs.getInt("score"),
            Sql.enumValue(rs, "band", ReadinessBand.class),
            Sql.enumValue(rs, "worst_gap_type", GapType.class),
            Sql.enumValue(rs, "worst_severity", GapSeverity.class),
            rs.getInt("open_gaps"),
            Sql.instant(rs, "computed_at"));

    // --- writes ---------------------------------------------------------------------------------

    /**
     * Persist one recomputation: supersede the previous current snapshot, insert the new one, then
     * reconcile the gap set.
     *
     * <p>One transaction on purpose. A snapshot whose gaps were half written would show a score
     * that no gap set explains, and the at-risk feed would be lying about a real transaction.
     *
     * @return the {@code snapshot_id} that was written
     */
    @Transactional
    public String write(ReadinessSnapshot snapshot, RecomputeTrigger trigger, String triggerEventId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        jdbc.update("""
                UPDATE pdei.readiness_snapshots
                   SET is_current = FALSE, updated_at = now()
                 WHERE transaction_id = :transactionId
                   AND is_current
                   AND reason_code IS NOT DISTINCT FROM CAST(:reasonCode AS VARCHAR)
                """,
                new MapSqlParameterSource()
                        .addValue("transactionId", snapshot.transactionId())
                        .addValue("reasonCode", Sql.name(snapshot.reasonCode())));

        Counts counts = Counts.of(snapshot);
        PolicyRef policy = PolicyRef.parse(snapshot.policyVersionId());

        jdbc.update("""
                INSERT INTO pdei.readiness_snapshots (
                    snapshot_id, transaction_id, merchant_id, reason_code,
                    score, band, base_score, penalty_total,
                    satisfied_weight, total_weight, mandatory_total, mandatory_satisfied,
                    recommended_total, recommended_satisfied, gap_count, contradiction_count,
                    requirements, policy_id, policy_version, trigger_event_id, trigger_reason,
                    is_current, computed_at)
                VALUES (
                    :snapshotId, :transactionId, :merchantId, :reasonCode,
                    :score, :band, :baseScore, :penaltyTotal,
                    :satisfiedWeight, :totalWeight, :mandatoryTotal, :mandatorySatisfied,
                    :recommendedTotal, :recommendedSatisfied, :gapCount, :contradictionCount,
                    CAST(:requirements AS jsonb),
                    (SELECT p.policy_id FROM pdei.policies p WHERE p.policy_id = :policyId),
                    :policyVersion, :triggerEventId,
                    :triggerReason, TRUE, :computedAt)
                ON CONFLICT (snapshot_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("snapshotId", snapshot.snapshotId())
                        .addValue("transactionId", snapshot.transactionId())
                        .addValue("merchantId", snapshot.merchantId())
                        .addValue("reasonCode", Sql.name(snapshot.reasonCode()))
                        .addValue("score", snapshot.score())
                        .addValue("band", Sql.name(snapshot.band()))
                        .addValue("baseScore", Math.round(snapshot.baseScore()))
                        .addValue("penaltyTotal", snapshot.penaltyPoints())
                        .addValue("satisfiedWeight", counts.satisfiedWeight())
                        .addValue("totalWeight", counts.totalWeight())
                        .addValue("mandatoryTotal", counts.mandatoryTotal())
                        .addValue("mandatorySatisfied", counts.mandatorySatisfied())
                        .addValue("recommendedTotal", counts.recommendedTotal())
                        .addValue("recommendedSatisfied", counts.recommendedSatisfied())
                        .addValue("gapCount", snapshot.gaps().size())
                        .addValue("contradictionCount", snapshot.contradictions().size())
                        .addValue("requirements", Json.write(snapshot.requirements()))
                        .addValue("policyId", policy.policyId())
                        .addValue("policyVersion", policy.version())
                        .addValue("triggerEventId", triggerEventId)
                        .addValue("triggerReason", Sql.name(trigger))
                        .addValue("computedAt", Sql.timestamp(snapshot.computedAt())));

        writeGaps(snapshot);
        return snapshot.snapshotId();
    }

    /**
     * Reconcile the gap set of a transaction against the gaps this snapshot detected.
     *
     * <p>Anything not in the new set is resolved; everything in it is inserted or refreshed and
     * explicitly un-resolved, because a gap that comes back is the same gap, not a new one.
     */
    private void writeGaps(ReadinessSnapshot snapshot) {
        List<String> currentIds = snapshot.gaps().stream().map(ReadinessGap::gapId).toList();

        jdbc.update("""
                UPDATE pdei.readiness_gaps
                   SET resolved = TRUE, resolved_at = :at, updated_at = now()
                 WHERE transaction_id = :transactionId
                   AND NOT resolved
                   AND gap_id NOT IN (:currentIds)
                """,
                new MapSqlParameterSource()
                        .addValue("at", Sql.timestamp(snapshot.computedAt()))
                        .addValue("transactionId", snapshot.transactionId())
                        .addValue("currentIds", Sql.nonEmpty(currentIds)));

        if (snapshot.gaps().isEmpty()) {
            return;
        }

        Map<EvidenceType, RequirementStrength> strengthByType = new HashMap<>();
        for (RequirementView requirement : snapshot.requirements()) {
            if (requirement.type() != null) {
                strengthByType.put(requirement.type(), requirement.strength());
            }
        }
        Set<EvidenceType> mandatoryTypes = strengthByType.entrySet().stream()
                .filter(e -> e.getValue() == RequirementStrength.MANDATORY)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());

        List<MapSqlParameterSource> batch = new ArrayList<>(snapshot.gaps().size());
        for (ReadinessGap gap : snapshot.gaps()) {
            ContradictionView contradiction = matchContradiction(gap, snapshot.contradictions());
            batch.add(new MapSqlParameterSource()
                    .addValue("gapId", gap.gapId())
                    .addValue("snapshotId", snapshot.snapshotId())
                    .addValue("transactionId", gap.transactionId() == null
                            ? snapshot.transactionId() : gap.transactionId())
                    .addValue("merchantId", snapshot.merchantId())
                    .addValue("type", Sql.name(gap.type()))
                    .addValue("severity", Sql.name(gap.severity()))
                    .addValue("evidenceType", Sql.name(gap.evidenceType()))
                    .addValue("evidenceId", evidenceFkOrNull(gap.evidenceId()))
                    .addValue("relatedEvidenceId", contradiction == null ? null : contradiction.right())
                    .addValue("requirementStrength", Sql.name(strengthByType.get(gap.evidenceType())))
                    .addValue("detail", gap.detail())
                    .addValue("remediation", Remediation.forGap(gap))
                    .addValue("penaltyApplied", penaltyFor(gap, mandatoryTypes))
                    .addValue("detectedAt", Sql.timestamp(gap.detectedAt()))
                    .addValue("metadata", metadataFor(gap, contradiction)));
        }

        jdbc.batchUpdate("""
                INSERT INTO pdei.readiness_gaps (
                    gap_id, snapshot_id, transaction_id, merchant_id, type, severity,
                    evidence_type, evidence_id, related_evidence_id, requirement_strength,
                    detail, remediation, penalty_applied, detected_at, resolved, resolved_at, metadata)
                VALUES (
                    :gapId, :snapshotId, :transactionId, :merchantId, :type, :severity,
                    :evidenceType, :evidenceId, :relatedEvidenceId, :requirementStrength,
                    :detail, :remediation, :penaltyApplied, :detectedAt, FALSE, NULL,
                    CAST(:metadata AS jsonb))
                ON CONFLICT (gap_id) DO UPDATE SET
                    snapshot_id          = EXCLUDED.snapshot_id,
                    severity             = EXCLUDED.severity,
                    requirement_strength = EXCLUDED.requirement_strength,
                    related_evidence_id  = EXCLUDED.related_evidence_id,
                    detail               = EXCLUDED.detail,
                    remediation          = EXCLUDED.remediation,
                    penalty_applied      = EXCLUDED.penalty_applied,
                    metadata             = EXCLUDED.metadata,
                    resolved             = FALSE,
                    resolved_at          = NULL,
                    updated_at           = now()
                """, batch.toArray(new MapSqlParameterSource[0]));
    }

    /**
     * Maintain the denormalised readiness projection on {@code pdei.transactions}.
     *
     * <p>The authoritative history is {@code readiness_snapshots}; this is the column set the
     * transaction list and the band filter of {@code GET /transactions?band=} read, and keeping it
     * current here is cheaper than joining the snapshot table on every page of that list.
     */
    @Transactional
    public boolean updateTransactionProjection(String transactionId, int score, ReadinessBand band,
                                               Instant computedAt) {
        int updated = jdbc.update("""
                UPDATE pdei.transactions
                   SET readiness_score = :score,
                       readiness_band = :band,
                       readiness_computed_at = :computedAt,
                       updated_at = now()
                 WHERE transaction_id = :transactionId
                   AND (readiness_computed_at IS NULL OR readiness_computed_at <= :computedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("score", score)
                        .addValue("band", Sql.name(band))
                        .addValue("computedAt", Sql.timestamp(computedAt))
                        .addValue("transactionId", transactionId));
        return updated > 0;
    }

    // --- port reads -----------------------------------------------------------------------------

    @Override
    public void saveSnapshot(ReadinessSnapshot snapshot) {
        write(snapshot, RecomputeTrigger.MANUAL_RECOMPUTE, null);
    }

    @Override
    public Optional<ReadinessSnapshot> findLatest(String transactionId) {
        return jdbc.query("""
                SELECT * FROM pdei.readiness_snapshots
                 WHERE transaction_id = :transactionId
                 ORDER BY is_current DESC, computed_at DESC
                 LIMIT 1
                """, new MapSqlParameterSource("transactionId", transactionId), snapshotMapper)
                .stream().findFirst();
    }

    @Override
    public List<ReadinessSnapshot> findLatestForMerchant(String merchantId, int limit) {
        return jdbc.query("""
                SELECT DISTINCT ON (transaction_id) *
                  FROM pdei.readiness_snapshots
                 WHERE merchant_id = :merchantId
                 ORDER BY transaction_id, computed_at DESC
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("merchantId", merchantId)
                        .addValue("limit", Math.max(1, limit)), snapshotMapper);
    }

    @Override
    public List<ReadinessGap> findGaps(String merchantId, GapType type, GapSeverity severity,
                                       int page, int size) {
        return jdbc.query("""
                SELECT * FROM pdei.readiness_gaps
                 WHERE NOT resolved
                   AND (CAST(:merchantId AS VARCHAR) IS NULL OR merchant_id = :merchantId)
                   AND (CAST(:type AS VARCHAR) IS NULL OR type = :type)
                   AND (CAST(:severity AS VARCHAR) IS NULL OR severity = :severity)
                 ORDER BY CASE severity
                            WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                          detected_at DESC
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("merchantId", merchantId)
                        .addValue("type", Sql.name(type))
                        .addValue("severity", Sql.name(severity))
                        .addValue("limit", Math.max(1, size))
                        .addValue("offset", Sql.offset(page, size)), GAP_MAPPER);
    }

    @Override
    public List<ReadinessGap> findGapsForTransaction(String transactionId) {
        return jdbc.query("""
                SELECT * FROM pdei.readiness_gaps
                 WHERE transaction_id = :transactionId AND NOT resolved
                 ORDER BY detected_at, gap_id
                """, new MapSqlParameterSource("transactionId", transactionId), GAP_MAPPER);
    }

    // --- at-risk feed ---------------------------------------------------------------------------

    /**
     * The at-risk feed behind {@code GET /api/v1/gaps}: current snapshots in a losing band, ranked
     * by the worst unresolved gap they carry.
     *
     * <p>{@code LEFT JOIN} on purpose - a transaction can be NOT_READY with zero gap rows (nothing
     * is attached at all yet), and that is precisely the case a merchant most needs to see.
     */
    public List<AtRiskEntry> findAtRisk(Set<ReadinessBand> bands, int limit) {
        List<String> bandNames = bands == null || bands.isEmpty()
                ? List.of(ReadinessBand.AT_RISK.name(), ReadinessBand.NOT_READY.name())
                : bands.stream().map(ReadinessBand::name).toList();

        return jdbc.query("""
                SELECT s.transaction_id,
                       s.merchant_id,
                       s.score,
                       s.band,
                       s.computed_at,
                       COALESCE(g.open_gaps, 0)   AS open_gaps,
                       g.worst_severity           AS worst_severity,
                       g.worst_gap_type           AS worst_gap_type
                  FROM pdei.readiness_snapshots s
                  LEFT JOIN (
                       SELECT transaction_id,
                              count(*) AS open_gaps,
                              (ARRAY_AGG(severity ORDER BY CASE severity
                                    WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                                    WHEN 'MEDIUM' THEN 2 ELSE 3 END))[1] AS worst_severity,
                              (ARRAY_AGG(type ORDER BY CASE severity
                                    WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                                    WHEN 'MEDIUM' THEN 2 ELSE 3 END))[1] AS worst_gap_type
                         FROM pdei.readiness_gaps
                        WHERE NOT resolved
                        GROUP BY transaction_id
                  ) g ON g.transaction_id = s.transaction_id
                 WHERE s.is_current
                   AND s.band IN (:bands)
                 ORDER BY CASE g.worst_severity
                            WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                          s.score ASC,
                          s.computed_at DESC
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("bands", bandNames)
                        .addValue("limit", Math.max(1, limit)), AT_RISK_MAPPER);
    }

    /**
     * Transactions whose current snapshot has gone stale, plus transactions that have never been
     * scored at all. Both need a recomputation before anyone trusts the feed.
     */
    public List<AtRiskEntry> findStale(Instant computedBefore, int limit) {
        return jdbc.query("""
                SELECT t.transaction_id,
                       t.merchant_id,
                       COALESCE(t.readiness_score, 0)                        AS score,
                       COALESCE(t.readiness_band, 'NOT_READY')               AS band,
                       t.readiness_computed_at                               AS computed_at,
                       0                                                     AS open_gaps,
                       CAST(NULL AS VARCHAR)                                 AS worst_severity,
                       CAST(NULL AS VARCHAR)                                 AS worst_gap_type
                  FROM pdei.transactions t
                 WHERE t.readiness_computed_at IS NULL
                    OR t.readiness_computed_at < :before
                 ORDER BY t.readiness_computed_at NULLS FIRST
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("before", Sql.timestamp(computedBefore))
                        .addValue("limit", Math.max(1, limit)), AT_RISK_MAPPER);
    }

    /** Unresolved gap count per severity for a merchant; feeds the control-tower KPI tiles. */
    public Map<GapSeverity, Long> countOpenGapsBySeverity(String merchantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT severity, count(*) AS total
                  FROM pdei.readiness_gaps
                 WHERE NOT resolved
                   AND (CAST(:merchantId AS VARCHAR) IS NULL OR merchant_id = :merchantId)
                 GROUP BY severity
                """, new MapSqlParameterSource("merchantId", merchantId));

        Map<GapSeverity, Long> counts = new EnumMap<>(GapSeverity.class);
        for (Map<String, Object> row : rows) {
            Object severity = row.get("severity");
            Object total = row.get("total");
            if (severity == null || total == null) {
                continue;
            }
            try {
                counts.put(GapSeverity.valueOf(severity.toString()), ((Number) total).longValue());
            } catch (IllegalArgumentException e) {
                log.debug("unknown gap severity in readiness_gaps: {}", severity);
            }
        }
        return counts;
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Penalty points this gap contributed to the score, recomputed with the same rules the engine
     * used so the row explains the number rather than restating it.
     */
    private static int penaltyFor(ReadinessGap gap, Set<EvidenceType> mandatoryTypes) {
        return ReadinessEngine.penaltyPoints(List.of(gap), mandatoryTypes);
    }

    /**
     * Contradiction that produced a CONTRADICTORY gap, matched on the pair
     * ({@code left} -> {@code evidenceId}, {@code detail}) that {@code GapDetector} copies across.
     */
    private static ContradictionView matchContradiction(ReadinessGap gap,
                                                        List<ContradictionView> contradictions) {
        if (gap.type() != GapType.CONTRADICTORY || contradictions.isEmpty()) {
            return null;
        }
        return contradictions.stream()
                .filter(c -> Objects.equals(c.left(), gap.evidenceId())
                        && Objects.equals(c.detail(), gap.detail()))
                .findFirst()
                .orElse(null);
    }

    /**
     * {@code readiness_gaps.metadata} carries what the columns cannot: the expiry instant of an
     * expiring artifact, and the full contradiction record (field, both values) for a
     * CONTRADICTORY gap. Without this the API could show that two documents disagree but not
     * about what.
     */
    private static String metadataFor(ReadinessGap gap, ContradictionView contradiction) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (gap.expiresAt() != null) {
            metadata.put("expiresAt", gap.expiresAt().toString());
        }
        if (contradiction != null) {
            metadata.put("contradiction", contradiction);
        }
        return metadata.isEmpty() ? null : Json.write(metadata);
    }

    private static Instant expiresAtFromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            var node = Json.readTree(metadata);
            return node != null && node.hasNonNull("expiresAt")
                    ? Instant.parse(node.get("expiresAt").asText()) : null;
        } catch (RuntimeException e) {
            log.debug("unparseable gap metadata, ignoring expiresAt: {}", e.toString());
            return null;
        }
    }

    /**
     * Rebuild the contradiction list of a snapshot from its CONTRADICTORY gap rows.
     *
     * <p>{@code V6__readiness.sql} has no contradictions column: the gap row plus its metadata is
     * the storage. Reading them back here keeps {@link ReadinessSnapshot#contradictions()}
     * populated for consumers that only ever see a persisted snapshot.
     */
    private static List<ContradictionView> contradictionsFrom(List<ReadinessGap> gaps) {
        List<ContradictionView> contradictions = new ArrayList<>();
        for (ReadinessGap gap : gaps) {
            if (gap.type() != GapType.CONTRADICTORY) {
                continue;
            }
            contradictions.add(new ContradictionView(gap.evidenceId(), null, null, gap.detail(),
                    gap.severity(), null, null, gap.detectedAt()));
        }
        return List.copyOf(contradictions);
    }

    /**
     * {@code fk_readiness_gaps_evidence} points at {@code pdei.evidence}, but a CONTRADICTORY gap
     * names whatever entity carried the conflicting fact - a shipment, a delivery, the transaction
     * itself. Anything that is not an {@code EV-} id is dropped from the column (it survives in
     * {@code detail} and {@code metadata}) rather than violating the foreign key.
     */
    private static String evidenceFkOrNull(String evidenceId) {
        return Ids.hasPrefix(evidenceId, IdPrefix.EVIDENCE) ? evidenceId : null;
    }

    private static <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(json, type);
        } catch (Exception e) {
            log.warn("could not deserialise persisted readiness detail: {}", e.toString());
            return List.of();
        }
    }

    private static String policyVersionId(String policyId, Integer version) {
        if (policyId == null) {
            return null;
        }
        return version == null ? policyId : policyId + "-V" + version;
    }

    /**
     * {@code readiness_snapshots} stores the policy as an FK plus a version number, while
     * {@link ReadinessSnapshot} carries a single {@code policyVersionId} string. This splits the
     * one into the other, and returns nulls for the deterministic default policy (which has no row
     * in {@code pdei.policies} and would violate the foreign key).
     */
    private record PolicyRef(String policyId, Integer version) {

        static PolicyRef parse(String policyVersionId) {
            if (policyVersionId == null || policyVersionId.isBlank()) {
                return new PolicyRef(null, null);
            }
            int marker = policyVersionId.lastIndexOf("-V");
            if (marker <= 0) {
                return new PolicyRef(policyVersionId, null);
            }
            String tail = policyVersionId.substring(marker + 2);
            try {
                return new PolicyRef(policyVersionId.substring(0, marker), Integer.valueOf(tail));
            } catch (NumberFormatException e) {
                return new PolicyRef(policyVersionId, null);
            }
        }
    }

    /** Requirement roll-up written alongside the score so the UI needs no second query. */
    private record Counts(int satisfiedWeight, int totalWeight, int mandatoryTotal,
                          int mandatorySatisfied, int recommendedTotal, int recommendedSatisfied) {

        static Counts of(ReadinessSnapshot snapshot) {
            int satisfiedWeight = 0;
            int totalWeight = 0;
            int mandatoryTotal = 0;
            int mandatorySatisfied = 0;
            int recommendedTotal = 0;
            int recommendedSatisfied = 0;

            for (RequirementView requirement : snapshot.requirements()) {
                RequirementStrength strength = requirement.strength();
                if (strength == RequirementStrength.MANDATORY) {
                    mandatoryTotal++;
                    totalWeight += requirement.weight();
                    if (requirement.satisfied()) {
                        mandatorySatisfied++;
                        satisfiedWeight += requirement.weight();
                    }
                } else if (strength == RequirementStrength.RECOMMENDED) {
                    recommendedTotal++;
                    totalWeight += requirement.weight();
                    if (requirement.satisfied()) {
                        recommendedSatisfied++;
                        satisfiedWeight += requirement.weight();
                    }
                }
            }
            return new Counts(satisfiedWeight, totalWeight, mandatoryTotal, mandatorySatisfied,
                    recommendedTotal, recommendedSatisfied);
        }
    }

    /** Plain-language next action for a gap; what a merchant operator actually needs to read. */
    private static final class Remediation {

        private Remediation() {
        }

        static String forGap(ReadinessGap gap) {
            if (gap.type() == null) {
                return null;
            }
            return switch (gap.type()) {
                case MISSING -> "attach " + describe(gap.evidenceType()) + " to this transaction";
                case EXPIRED -> "re-capture " + describe(gap.evidenceType())
                        + ": the attached artifact is past its retention window";
                case EXPIRING_SOON -> "refresh " + describe(gap.evidenceType()) + " before it expires";
                case CONTRADICTORY -> "reconcile the conflicting facts, then re-upload the corrected artifact";
                case UNVERIFIABLE_PROVENANCE -> "re-ingest through a source system so the artifact"
                        + " carries a source event id and content hash";
                case LOW_QUALITY -> "replace with a legible copy: the current one failed extraction quality";
                case VERSION_CONFLICT -> "supersede the stale versions so exactly one is current";
            };
        }

        private static String describe(EvidenceType type) {
            return type == null ? "the required evidence" : type.name();
        }
    }
}
