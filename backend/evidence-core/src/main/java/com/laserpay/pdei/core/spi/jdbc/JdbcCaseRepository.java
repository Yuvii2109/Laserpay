package com.laserpay.pdei.core.spi.jdbc;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.core.model.FunnelMetrics;
import com.laserpay.pdei.core.model.PackageManifest;
import com.laserpay.pdei.core.spi.AdmissionLogRecord;
import com.laserpay.pdei.core.spi.CaseEvidenceRecord;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.InvestigationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC adapter for the dispute side of the schema: {@code disputes}, {@code dispute_cases},
 * {@code case_evidence}, {@code investigations} and {@code ai_admission_log}.
 */
public class JdbcCaseRepository implements CaseRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcCaseRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DisputeView> DISPUTE = (rs, i) -> new DisputeView(
            rs.getString("id"), rs.getString("merchant_id"), rs.getString("transaction_id"),
            JdbcSupport.enumValue(rs, "reason_code", DisputeReasonCode.class),
            JdbcSupport.enumValue(rs, "status", DisputeStatus.class),
            JdbcSupport.money(rs, "amount_minor", "currency"), rs.getString("network_case_ref"),
            rs.getString("source"), JdbcSupport.instant(rs, "opened_at"),
            JdbcSupport.instant(rs, "deadline_at"), JdbcSupport.instant(rs, "closed_at"),
            JdbcSupport.instant(rs, "updated_at"));

    private static final RowMapper<CaseView> CASE = (rs, i) -> new CaseView(
            rs.getString("id"), rs.getString("dispute_id"), rs.getString("merchant_id"),
            rs.getString("transaction_id"), JdbcSupport.enumValue(rs, "status", CaseStatus.class),
            rs.getString("workflow_id"), rs.getString("assigned_to"), rs.getInt("package_version"),
            JdbcSupport.instant(rs, "opened_at"), JdbcSupport.instant(rs, "updated_at"),
            JdbcSupport.instant(rs, "closed_at"));

    private static final RowMapper<CaseEvidenceRecord> CASE_EVIDENCE = (rs, i) -> new CaseEvidenceRecord(
            rs.getString("case_id"), rs.getString("evidence_id"),
            JdbcSupport.enumValue(rs, "strength", RequirementStrength.class), rs.getInt("position"),
            rs.getString("sha256_at_selection"), JdbcSupport.instant(rs, "attached_at"));

    private static final RowMapper<InvestigationRecord> INVESTIGATION = (rs, i) -> new InvestigationRecord(
            rs.getString("id"), rs.getString("case_id"), rs.getString("dispute_id"),
            rs.getString("merchant_id"), rs.getString("transaction_id"),
            JdbcSupport.enumValue(rs, "classification", InvestigationClassification.class),
            rs.getDouble("confidence"),
            JdbcSupport.enumValue(rs, "recommended_action", RecommendedAction.class),
            JdbcSupport.enumValue(rs, "safety_decision", SafetyDecision.class),
            rs.getString("provider"), rs.getString("model"), rs.getLong("latency_ms"),
            rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"), rs.getInt("attempt"),
            rs.getString("reasoning_summary"), rs.getString("narrative"), rs.getString("result_json"),
            rs.getString("verdict_json"), JdbcSupport.instant(rs, "started_at"),
            JdbcSupport.instant(rs, "completed_at"));

    // --- disputes -------------------------------------------------------------------------------

    @Override
    public void insertDispute(DisputeView dispute) {
        jdbc.update("""
                INSERT INTO pdei.disputes (id, merchant_id, transaction_id, reason_code, status,
                    amount_minor, currency, network_case_ref, source, opened_at, deadline_at, closed_at,
                    updated_at)
                VALUES (:id, :merchantId, :transactionId, :reasonCode, :status, :amountMinor, :currency,
                    :networkCaseRef, :source, :openedAt, :deadlineAt, :closedAt, :updatedAt)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", dispute.disputeId())
                        .addValue("merchantId", dispute.merchantId())
                        .addValue("transactionId", dispute.transactionId())
                        .addValue("reasonCode", JdbcSupport.name(dispute.reasonCode()))
                        .addValue("status", JdbcSupport.name(dispute.status()))
                        .addValue("amountMinor",
                                dispute.amount() == null ? null : dispute.amount().amountMinor())
                        .addValue("currency", dispute.amount() == null ? null : dispute.amount().currency())
                        .addValue("networkCaseRef", dispute.networkCaseRef())
                        .addValue("source", dispute.source())
                        .addValue("openedAt", JdbcSupport.timestamp(dispute.openedAt()))
                        .addValue("deadlineAt", JdbcSupport.timestamp(dispute.deadlineAt()))
                        .addValue("closedAt", JdbcSupport.timestamp(dispute.closedAt()))
                        .addValue("updatedAt", JdbcSupport.timestamp(dispute.updatedAt())));
    }

    @Override
    public Optional<DisputeView> findDispute(String disputeId) {
        return jdbc.query("SELECT * FROM pdei.disputes WHERE id = :id", Map.of("id", disputeId), DISPUTE)
                .stream().findFirst();
    }

    @Override
    public Optional<DisputeView> findOpenDisputeForTransaction(String transactionId) {
        return jdbc.query("""
                        SELECT * FROM pdei.disputes
                         WHERE transaction_id = :tx
                           AND status NOT IN ('WON','LOST','EXPIRED','WITHDRAWN')
                         ORDER BY opened_at DESC LIMIT 1
                        """, Map.of("tx", transactionId), DISPUTE)
                .stream().findFirst();
    }

    @Override
    public List<DisputeView> findDisputes(String merchantId, DisputeStatus status,
                                          DisputeReasonCode reasonCode, int page, int size) {
        return jdbc.query("""
                SELECT * FROM pdei.disputes
                 WHERE (:merchant IS NULL OR merchant_id = :merchant)
                   AND (:status IS NULL OR status = :status)
                   AND (:reason IS NULL OR reason_code = :reason)
                 ORDER BY opened_at DESC LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("status", JdbcSupport.name(status))
                        .addValue("reason", JdbcSupport.name(reasonCode))
                        .addValue("limit", Math.max(1, size))
                        .addValue("offset", JdbcSupport.offset(page, size)), DISPUTE);
    }

    @Override
    public boolean updateDisputeStatus(String disputeId, DisputeStatus status, Instant at,
                                       Instant closedAt) {
        return jdbc.update("""
                UPDATE pdei.disputes SET status = :status, updated_at = :at, closed_at = :closedAt
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("status", JdbcSupport.name(status))
                        .addValue("at", JdbcSupport.timestamp(at))
                        .addValue("closedAt", JdbcSupport.timestamp(closedAt))
                        .addValue("id", disputeId)) > 0;
    }

    @Override
    public double merchantWinRate(String merchantId) {
        Double rate = jdbc.queryForObject("""
                SELECT COALESCE(
                         count(*) FILTER (WHERE status = 'WON')::float8
                         / NULLIF(count(*) FILTER (WHERE status IN ('WON','LOST')), 0), 0)
                  FROM pdei.disputes WHERE merchant_id = :merchant
                """, Map.of("merchant", merchantId), Double.class);
        return rate == null ? 0.0d : rate;
    }

    @Override
    public int similarCaseCount(String merchantId, DisputeReasonCode reasonCode) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pdei.disputes
                 WHERE merchant_id = :merchant
                   AND (:reason IS NULL OR reason_code = :reason)
                   AND status IN ('WON','LOST')
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("reason", JdbcSupport.name(reasonCode)), Integer.class);
        return count == null ? 0 : count;
    }

    // --- cases ----------------------------------------------------------------------------------

    @Override
    public Optional<CaseView> findCase(String caseId) {
        return jdbc.query("SELECT * FROM pdei.dispute_cases WHERE id = :id", Map.of("id", caseId), CASE)
                .stream().findFirst();
    }

    @Override
    public Optional<CaseView> findCaseByDispute(String disputeId) {
        return jdbc.query("""
                        SELECT * FROM pdei.dispute_cases WHERE dispute_id = :id
                         ORDER BY opened_at DESC LIMIT 1
                        """, Map.of("id", disputeId), CASE)
                .stream().findFirst();
    }

    @Override
    public List<CaseView> findCases(String merchantId, CaseStatus status, int page, int size) {
        return jdbc.query("""
                SELECT * FROM pdei.dispute_cases
                 WHERE (:merchant IS NULL OR merchant_id = :merchant)
                   AND (:status IS NULL OR status = :status)
                 ORDER BY opened_at DESC LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("status", JdbcSupport.name(status))
                        .addValue("limit", Math.max(1, size))
                        .addValue("offset", JdbcSupport.offset(page, size)), CASE);
    }

    @Override
    public boolean updateCaseStatus(String caseId, CaseStatus status, Instant at) {
        return jdbc.update("""
                UPDATE pdei.dispute_cases SET status = :status, updated_at = :at,
                       closed_at = CASE WHEN :status IN ('CLOSED','FAILED') THEN :at ELSE closed_at END
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("status", JdbcSupport.name(status))
                        .addValue("at", JdbcSupport.timestamp(at))
                        .addValue("id", caseId)) > 0;
    }

    @Override
    public void updateCasePackage(String caseId, int packageVersion, String manifestJson, Instant at) {
        jdbc.update("""
                UPDATE pdei.dispute_cases
                   SET package_version = :version, manifest_json = CAST(:manifest AS jsonb), updated_at = :at
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("version", packageVersion)
                        .addValue("manifest", manifestJson)
                        .addValue("at", JdbcSupport.timestamp(at))
                        .addValue("id", caseId));
    }

    @Override
    public Optional<PackageManifest> findLatestManifest(String caseId) {
        List<String> rows = jdbc.queryForList(
                "SELECT manifest_json FROM pdei.dispute_cases WHERE id = :id", Map.of("id", caseId),
                String.class);
        return rows.stream()
                .filter(json -> json != null && !json.isBlank())
                .findFirst()
                .map(json -> {
                    try {
                        return Json.read(json, PackageManifest.class);
                    } catch (RuntimeException e) {
                        log.warn("could not deserialise manifest for case {}: {}", caseId, e.toString());
                        return null;
                    }
                });
    }

    // --- case evidence --------------------------------------------------------------------------

    @Override
    public void replaceCaseEvidence(String caseId, List<CaseEvidenceRecord> evidence) {
        jdbc.update("DELETE FROM pdei.case_evidence WHERE case_id = :id", Map.of("id", caseId));
        if (evidence == null || evidence.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> params = new ArrayList<>();
        for (CaseEvidenceRecord record : evidence) {
            params.add(new MapSqlParameterSource()
                    .addValue("caseId", record.caseId())
                    .addValue("evidenceId", record.evidenceId())
                    .addValue("strength", JdbcSupport.name(record.strength()))
                    .addValue("position", record.position())
                    .addValue("sha256", record.sha256AtSelection())
                    .addValue("attachedAt", JdbcSupport.timestamp(record.attachedAt())));
        }
        jdbc.batchUpdate("""
                INSERT INTO pdei.case_evidence (case_id, evidence_id, strength, position,
                    sha256_at_selection, attached_at)
                VALUES (:caseId, :evidenceId, :strength, :position, :sha256, :attachedAt)
                ON CONFLICT (case_id, evidence_id) DO UPDATE
                    SET strength = EXCLUDED.strength,
                        position = EXCLUDED.position,
                        sha256_at_selection = EXCLUDED.sha256_at_selection,
                        attached_at = EXCLUDED.attached_at
                """, params.toArray(new MapSqlParameterSource[0]));
    }

    @Override
    public List<CaseEvidenceRecord> findCaseEvidence(String caseId) {
        return jdbc.query("SELECT * FROM pdei.case_evidence WHERE case_id = :id ORDER BY position",
                Map.of("id", caseId), CASE_EVIDENCE);
    }

    // --- investigations -------------------------------------------------------------------------

    @Override
    public void saveInvestigation(InvestigationRecord investigation) {
        jdbc.update("""
                INSERT INTO pdei.investigations (id, case_id, dispute_id, merchant_id, transaction_id,
                    classification, confidence, recommended_action, safety_decision, provider, model,
                    latency_ms, prompt_tokens, completion_tokens, attempt, reasoning_summary, narrative,
                    result_json, verdict_json, started_at, completed_at)
                VALUES (:id, :caseId, :disputeId, :merchantId, :transactionId, :classification, :confidence,
                    :recommendedAction, :safetyDecision, :provider, :model, :latencyMs, :promptTokens,
                    :completionTokens, :attempt, :reasoningSummary, :narrative, CAST(:result AS jsonb),
                    CAST(:verdict AS jsonb), :startedAt, :completedAt)
                ON CONFLICT (id) DO UPDATE
                    SET classification = EXCLUDED.classification,
                        confidence = EXCLUDED.confidence,
                        recommended_action = EXCLUDED.recommended_action,
                        safety_decision = EXCLUDED.safety_decision,
                        result_json = EXCLUDED.result_json,
                        verdict_json = EXCLUDED.verdict_json,
                        completed_at = EXCLUDED.completed_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", investigation.investigationId())
                        .addValue("caseId", investigation.caseId())
                        .addValue("disputeId", investigation.disputeId())
                        .addValue("merchantId", investigation.merchantId())
                        .addValue("transactionId", investigation.transactionId())
                        .addValue("classification", JdbcSupport.name(investigation.classification()))
                        .addValue("confidence", investigation.confidence())
                        .addValue("recommendedAction", JdbcSupport.name(investigation.recommendedAction()))
                        .addValue("safetyDecision", JdbcSupport.name(investigation.safetyDecision()))
                        .addValue("provider", investigation.provider())
                        .addValue("model", investigation.model())
                        .addValue("latencyMs", investigation.latencyMs())
                        .addValue("promptTokens", investigation.promptTokens())
                        .addValue("completionTokens", investigation.completionTokens())
                        .addValue("attempt", investigation.attempt())
                        .addValue("reasoningSummary", investigation.reasoningSummary())
                        .addValue("narrative", investigation.narrative())
                        .addValue("result", investigation.resultJson())
                        .addValue("verdict", investigation.verdictJson())
                        .addValue("startedAt", JdbcSupport.timestamp(investigation.startedAt()))
                        .addValue("completedAt", JdbcSupport.timestamp(investigation.completedAt())));
    }

    @Override
    public Optional<InvestigationRecord> findInvestigation(String investigationId) {
        return jdbc.query("SELECT * FROM pdei.investigations WHERE id = :id",
                Map.of("id", investigationId), INVESTIGATION).stream().findFirst();
    }

    @Override
    public Optional<InvestigationRecord> findLatestInvestigationForCase(String caseId) {
        return jdbc.query("""
                        SELECT * FROM pdei.investigations WHERE case_id = :id
                         ORDER BY started_at DESC NULLS LAST, id DESC LIMIT 1
                        """, Map.of("id", caseId), INVESTIGATION).stream().findFirst();
    }

    @Override
    public void appendAdmissionLog(AdmissionLogRecord record) {
        jdbc.update("""
                INSERT INTO pdei.ai_admission_log (id, case_id, merchant_id, transaction_id, admitted,
                    priority, reason, short_circuit, financial_impact, deadline_urgency, ambiguity_score,
                    deterministic_confidence, dispute_amount_minor, currency, decided_at)
                VALUES (:id, :caseId, :merchantId, :transactionId, :admitted, :priority, :reason,
                    :shortCircuit, :financialImpact, :deadlineUrgency, :ambiguityScore,
                    :deterministicConfidence, :amountMinor, :currency, :decidedAt)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", record.admissionId())
                        .addValue("caseId", record.caseId())
                        .addValue("merchantId", record.merchantId())
                        .addValue("transactionId", record.transactionId())
                        .addValue("admitted", record.admitted())
                        .addValue("priority", record.priority())
                        .addValue("reason", record.reason())
                        .addValue("shortCircuit", record.shortCircuit())
                        .addValue("financialImpact", record.financialImpact())
                        .addValue("deadlineUrgency", record.deadlineUrgency())
                        .addValue("ambiguityScore", record.ambiguityScore())
                        .addValue("deterministicConfidence", record.deterministicConfidence())
                        .addValue("amountMinor", record.disputeAmountMinor())
                        .addValue("currency", record.currency())
                        .addValue("decidedAt", JdbcSupport.timestamp(record.decidedAt())));
    }

    /**
     * Funnel counters for {@code GET /api/v1/metrics/funnel}: events, dispute candidates, ambiguous
     * cases, cases actually sent to the model, cases a human touched, and cases prepared unattended.
     */
    @Override
    public FunnelMetrics funnel(String merchantId, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("merchant", merchantId)
                .addValue("from", JdbcSupport.timestamp(from))
                .addValue("to", JdbcSupport.timestamp(to));

        long events = count("SELECT count(*) FROM pdei.processed_events"
                + " WHERE (CAST(:from AS timestamptz) IS NULL OR processed_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR processed_at < :to)", params);
        long candidates = count("SELECT count(*) FROM pdei.disputes"
                + " WHERE (:merchant IS NULL OR merchant_id = :merchant)"
                + "   AND (CAST(:from AS timestamptz) IS NULL OR opened_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR opened_at < :to)", params);
        long ambiguous = count("SELECT count(*) FROM pdei.ai_admission_log"
                + " WHERE (:merchant IS NULL OR merchant_id = :merchant)"
                + "   AND short_circuit <> 'ALL_REQUIREMENTS_SATISFIED'"
                + "   AND (CAST(:from AS timestamptz) IS NULL OR decided_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR decided_at < :to)", params);
        long investigated = count("SELECT count(*) FROM pdei.ai_admission_log"
                + " WHERE (:merchant IS NULL OR merchant_id = :merchant) AND admitted = TRUE"
                + "   AND (CAST(:from AS timestamptz) IS NULL OR decided_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR decided_at < :to)", params);
        long humanReviewed = count("SELECT count(*) FROM pdei.investigations"
                + " WHERE (:merchant IS NULL OR merchant_id = :merchant)"
                + "   AND safety_decision IN ('ALLOW_WITH_REVIEW','DENY')"
                + "   AND (CAST(:from AS timestamptz) IS NULL OR started_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR started_at < :to)", params);
        long autoPrepared = count("SELECT count(*) FROM pdei.investigations"
                + " WHERE (:merchant IS NULL OR merchant_id = :merchant)"
                + "   AND safety_decision = 'ALLOW' AND recommended_action = 'PREPARE_REPRESENTMENT'"
                + "   AND (CAST(:from AS timestamptz) IS NULL OR started_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR started_at < :to)", params);
        long denied = count("SELECT count(*) FROM pdei.investigations"
                + " WHERE (:merchant IS NULL OR merchant_id = :merchant) AND safety_decision = 'DENY'"
                + "   AND (CAST(:from AS timestamptz) IS NULL OR started_at >= :from)"
                + "   AND (CAST(:to AS timestamptz) IS NULL OR started_at < :to)", params);

        return new FunnelMetrics(merchantId, from, to, events, candidates, ambiguous, investigated,
                humanReviewed, autoPrepared, denied);
    }

    private long count(String sql, MapSqlParameterSource params) {
        try {
            Long value = jdbc.queryForObject(sql, params, Long.class);
            return value == null ? 0L : value;
        } catch (RuntimeException e) {
            log.warn("funnel sub-query failed, reporting 0: {}", e.toString());
            return 0L;
        }
    }
}
