package com.laserpay.pdei.orchestrator.persistence;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.orchestrator.workflow.DisputeCaseWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place in the platform that writes {@code pdei.dispute_cases}.
 *
 * <p>{@code evidence-core}'s {@code CaseRepositoryPort} can read cases and update their status and
 * package, but it has no {@code insertCase}: creating the workflow aggregate is the orchestrator's
 * job, so the INSERT lives here rather than being pushed into the shared library. The same class
 * also owns the workflow-specific columns ({@code workflow_id}, {@code run_id},
 * {@code progress_percent}, {@code approval_*}, {@code failure_reason}) that no other service
 * writes.</p>
 *
 * <p><b>Idempotency.</b> {@link #insertIfAbsent} is {@code INSERT ... ON CONFLICT DO NOTHING} and
 * returns whether the row was newly created, so a retried {@code openCase} activity or a
 * redelivered {@code DisputeCreated} event is a no-op rather than a duplicate key error. Every
 * update is a whole-column assignment, so replaying one produces the same row.</p>
 *
 * <p><b>Primary-key column resolution.</b> The Flyway migration {@code V5__disputes.sql} names the
 * primary key {@code case_id}, while {@code evidence-core}'s JDBC adapters query a column called
 * {@code id}. Until that divergence is resolved in one place (see this module's context.md,
 * "Known gaps"), this class resolves the actual column name once from
 * {@code information_schema.columns} and caches it, so the orchestrator works against whichever
 * schema is deployed instead of failing at the first insert.</p>
 */
@Component
public class CaseWriter {

    private static final Logger log = LoggerFactory.getLogger(CaseWriter.class);

    private static final String TABLE = "pdei.dispute_cases";
    private static final String PREFERRED_ID_COLUMN = "case_id";
    private static final String LEGACY_ID_COLUMN = "id";
    private static final String PREFERRED_MANIFEST_COLUMN = "package_manifest";
    private static final String LEGACY_MANIFEST_COLUMN = "manifest_json";

    private final NamedParameterJdbcTemplate jdbc;

    private volatile String idColumn;
    private volatile String manifestColumn;

    public CaseWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- reads ------------------------------------------------------------------------------

    public Optional<CaseRow> find(String caseId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE " + idColumn() + " = :id",
                        Map.of("id", caseId), mapper())
                .stream().findFirst();
    }

    /** The most recently opened case for a dispute, which is the one the workflow adopts. */
    public Optional<CaseRow> findByDispute(String disputeId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE dispute_id = :disputeId"
                                + " ORDER BY opened_at DESC LIMIT 1",
                        Map.of("disputeId", disputeId), mapper())
                .stream().findFirst();
    }

    // --- writes -----------------------------------------------------------------------------

    /**
     * Create the case row if it is not already there.
     *
     * @return true when this call inserted the row; false when it already existed
     */
    public boolean insertIfAbsent(String caseId, String disputeId, String merchantId,
                                  String transactionId, Money amount, Instant openedAt,
                                  Instant deadlineAt, String workflowId, String runId, Instant now) {
        int inserted = jdbc.update("""
                        INSERT INTO %s (%s, dispute_id, merchant_id, transaction_id, status,
                                        amount_minor, currency, workflow_id, run_id, task_queue,
                                        progress_percent, opened_at, deadline_at, created_at, updated_at)
                        VALUES (:caseId, :disputeId, :merchantId, :transactionId, :status,
                                :amountMinor, :currency, :workflowId, :runId, :taskQueue,
                                :progressPercent, :openedAt, :deadlineAt, :now, :now)
                        ON CONFLICT DO NOTHING
                        """.formatted(TABLE, idColumn()),
                new MapSqlParameterSource()
                        .addValue("caseId", caseId)
                        .addValue("disputeId", disputeId)
                        .addValue("merchantId", merchantId)
                        .addValue("transactionId", transactionId)
                        .addValue("status", CaseStatus.CREATED.name())
                        .addValue("amountMinor", amount == null ? 0L : amount.amountMinor())
                        .addValue("currency", amount == null ? null : amount.currency())
                        .addValue("workflowId", workflowId)
                        .addValue("runId", runId)
                        .addValue("taskQueue", DisputeCaseWorkflow.TASK_QUEUE)
                        .addValue("progressPercent", 0)
                        .addValue("openedAt", timestamp(openedAt == null ? now : openedAt))
                        .addValue("deadlineAt", timestamp(deadlineAt))
                        .addValue("now", timestamp(now)));
        return inserted > 0;
    }

    /**
     * Bind the case row to the Temporal execution that is driving it. Always safe to repeat: a
     * continue-as-new produces a new runId for the same workflowId and this simply records it.
     */
    public void bindWorkflow(String caseId, String workflowId, String runId, Instant now) {
        jdbc.update("UPDATE " + TABLE + " SET workflow_id = :workflowId, run_id = :runId,"
                        + " task_queue = :taskQueue, updated_at = :now WHERE " + idColumn() + " = :id",
                new MapSqlParameterSource()
                        .addValue("workflowId", workflowId)
                        .addValue("runId", runId)
                        .addValue("taskQueue", DisputeCaseWorkflow.TASK_QUEUE)
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /**
     * Persist the workflow phase: status plus the 0-100 progress the query API reports.
     *
     * <p>{@code progress_percent} only ever moves forward ({@code GREATEST}), so an out-of-order
     * write from a retried activity cannot make a case appear to go backwards on the queue screen.
     * The {@code prepared_at} / {@code submitted_at} / {@code closed_at} stamps are first-write-wins
     * for the same reason.</p>
     */
    public void updateStatus(String caseId, CaseStatus status, int progressPercent, Instant now) {
        if (status == null) {
            return;
        }
        jdbc.update("""
                        UPDATE %s
                           SET status = CAST(:status AS VARCHAR),
                               progress_percent = GREATEST(progress_percent, :progress),
                               prepared_at = CASE WHEN CAST(:status AS VARCHAR) = 'PREPARED'
                                                  THEN COALESCE(prepared_at, :now) ELSE prepared_at END,
                               submitted_at = CASE WHEN CAST(:status AS VARCHAR) = 'SUBMITTED'
                                                   THEN COALESCE(submitted_at, :now) ELSE submitted_at END,
                               closed_at = CASE WHEN CAST(:status AS VARCHAR) IN ('CLOSED', 'FAILED')
                                                THEN COALESCE(closed_at, :now) ELSE closed_at END,
                               updated_at = :now
                         WHERE %s = :id
                        """.formatted(TABLE, idColumn()),
                new MapSqlParameterSource()
                        .addValue("status", status.name())
                        .addValue("progress", Math.max(0, Math.min(100, progressPercent)))
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /** Readiness is recomputed on every gather; the case row keeps the latest value for the UI. */
    public void updateReadiness(String caseId, int score, ReadinessBand band, Instant now) {
        jdbc.update("UPDATE " + TABLE + " SET readiness_score = :score, readiness_band = :band,"
                        + " updated_at = :now WHERE " + idColumn() + " = :id",
                new MapSqlParameterSource()
                        .addValue("score", Math.max(0, Math.min(100, score)))
                        .addValue("band", band == null ? null : band.name())
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /** What the investigation proposed and what the deterministic gate decided about it. */
    public void updateAssessment(String caseId, RecommendedAction action, SafetyDecision decision,
                                 Instant now) {
        jdbc.update("UPDATE " + TABLE + " SET recommended_action = :action, safety_decision = :decision,"
                        + " updated_at = :now WHERE " + idColumn() + " = :id",
                new MapSqlParameterSource()
                        .addValue("action", action == null ? null : action.name())
                        .addValue("decision", decision == null ? null : decision.name())
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /** Who signed the case off, when, and what they wrote. */
    public void updateApproval(String caseId, String actor, String notes, Instant decidedAt,
                               Instant now) {
        jdbc.update("UPDATE " + TABLE + " SET approval_actor = :actor, approval_notes = :notes,"
                        + " approval_at = :decidedAt, updated_at = :now WHERE " + idColumn() + " = :id",
                new MapSqlParameterSource()
                        .addValue("actor", actor)
                        .addValue("notes", notes)
                        .addValue("decidedAt", timestamp(decidedAt == null ? now : decidedAt))
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /** Package coordinates written by step 9, kept alongside the manifest evidence-core stores. */
    public void updatePackage(String caseId, int packageVersion, String bundleObjectKey, Instant now) {
        jdbc.update("UPDATE " + TABLE + " SET package_version = :version,"
                        + " package_object_key = :objectKey, updated_at = :now"
                        + " WHERE " + idColumn() + " = :id",
                new MapSqlParameterSource()
                        .addValue("version", packageVersion)
                        .addValue("objectKey", bundleObjectKey)
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /** Terminal marker. {@code failureReason} is null for a clean close. */
    public void markClosed(String caseId, CaseStatus status, String failureReason, Instant now) {
        jdbc.update("""
                        UPDATE %s
                           SET status = :status,
                               progress_percent = 100,
                               failure_reason = COALESCE(CAST(:failureReason AS VARCHAR), failure_reason),
                               closed_at = COALESCE(closed_at, :now),
                               updated_at = :now
                         WHERE %s = :id
                        """.formatted(TABLE, idColumn()),
                new MapSqlParameterSource()
                        .addValue("status", (status == null ? CaseStatus.CLOSED : status).name())
                        .addValue("failureReason", truncate(failureReason, 512))
                        .addValue("now", timestamp(now))
                        .addValue("id", caseId));
    }

    /** Column that holds the package manifest JSON, resolved the same way as the id column. */
    public String manifestColumn() {
        String resolved = manifestColumn;
        if (resolved == null) {
            resolved = resolveColumn(PREFERRED_MANIFEST_COLUMN, LEGACY_MANIFEST_COLUMN);
            manifestColumn = resolved;
        }
        return resolved;
    }

    // --- schema resolution --------------------------------------------------------------------

    String idColumn() {
        String resolved = idColumn;
        if (resolved == null) {
            resolved = resolveColumn(PREFERRED_ID_COLUMN, LEGACY_ID_COLUMN);
            idColumn = resolved;
        }
        return resolved;
    }

    private String resolveColumn(String preferred, String legacy) {
        try {
            List<String> present = jdbc.queryForList("""
                            SELECT column_name FROM information_schema.columns
                             WHERE table_schema = 'pdei' AND table_name = 'dispute_cases'
                               AND column_name IN (:preferred, :legacy)
                            """,
                    new MapSqlParameterSource().addValue("preferred", preferred)
                            .addValue("legacy", legacy),
                    String.class);
            if (present.contains(preferred)) {
                return preferred;
            }
            if (present.contains(legacy)) {
                log.warn("{} uses the legacy column '{}' rather than '{}'; see context.md known gaps",
                        TABLE, legacy, preferred);
                return legacy;
            }
        } catch (RuntimeException e) {
            log.warn("could not inspect {} columns ({}); assuming '{}'", TABLE, e.toString(), preferred);
        }
        return preferred;
    }

    // --- mapping ----------------------------------------------------------------------------

    private RowMapper<CaseRow> mapper() {
        String id = idColumn();
        return (ResultSet rs, int rowNum) -> new CaseRow(
                rs.getString(id),
                rs.getString("dispute_id"),
                rs.getString("merchant_id"),
                rs.getString("transaction_id"),
                enumValue(rs.getString("status")),
                money(rs),
                rs.getString("workflow_id"),
                rs.getString("run_id"),
                rs.getInt("package_version"),
                rs.getInt("progress_percent"),
                instant(rs, "opened_at"),
                instant(rs, "deadline_at"),
                instant(rs, "closed_at"));
    }

    private static Money money(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        if (currency == null) {
            return null;
        }
        return Money.of(rs.getLong("amount_minor"), currency.trim());
    }

    private static CaseStatus enumValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CaseStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("unknown case status '{}' in {}", raw, TABLE);
            return null;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
