package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One synthetic-world generation run ({@code SIM-} ids).
 *
 * <p>(seed, parameters) fully determine the generated data, so benchmarks are reproducible
 * (rule 11). {@code disputeRateBps} is an integer basis-point rate - even a rate never gets to
 * be a float in this codebase.
 */
@Entity
@Table(name = "simulation_runs", schema = PdeiSchema.NAME)
public class SimulationRunEntity extends VersionedEntity {

    /** PENDING|RUNNING|STOPPING|STOPPED|COMPLETED|FAILED. */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_STOPPING = "STOPPING";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "run_id", nullable = false, length = 64)
    private String id;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Column(name = "merchant_count", nullable = false)
    private int merchantCount;

    @Column(name = "transaction_count", nullable = false)
    private int transactionCount;

    @Column(name = "days", nullable = false)
    private int days = 1;

    @Column(name = "dispute_rate_bps", nullable = false)
    private int disputeRateBps;

    @Column(name = "failure_profile", length = 64)
    private String failureProfile;

    @Column(name = "scenario_key", length = 64)
    private String scenarioKey;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_PENDING;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "events_emitted", nullable = false)
    private long eventsEmitted;

    @Column(name = "transactions_created", nullable = false)
    private long transactionsCreated;

    @Column(name = "evidence_created", nullable = false)
    private long evidenceCreated;

    @Column(name = "disputes_created", nullable = false)
    private long disputesCreated;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "requested_by", length = 128)
    private String requestedBy;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb")
    private Map<String, Object> params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stats", columnDefinition = "jsonb")
    private Map<String, Object> stats;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public int getMerchantCount() {
        return merchantCount;
    }

    public void setMerchantCount(int merchantCount) {
        this.merchantCount = merchantCount;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        this.days = days;
    }

    public int getDisputeRateBps() {
        return disputeRateBps;
    }

    public void setDisputeRateBps(int disputeRateBps) {
        this.disputeRateBps = disputeRateBps;
    }

    public String getFailureProfile() {
        return failureProfile;
    }

    public void setFailureProfile(String failureProfile) {
        this.failureProfile = failureProfile;
    }

    public String getScenarioKey() {
        return scenarioKey;
    }

    public void setScenarioKey(String scenarioKey) {
        this.scenarioKey = scenarioKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public long getEventsEmitted() {
        return eventsEmitted;
    }

    public void setEventsEmitted(long eventsEmitted) {
        this.eventsEmitted = eventsEmitted;
    }

    public long getTransactionsCreated() {
        return transactionsCreated;
    }

    public void setTransactionsCreated(long transactionsCreated) {
        this.transactionsCreated = transactionsCreated;
    }

    public long getEvidenceCreated() {
        return evidenceCreated;
    }

    public void setEvidenceCreated(long evidenceCreated) {
        this.evidenceCreated = evidenceCreated;
    }

    public long getDisputesCreated() {
        return disputesCreated;
    }

    public void setDisputesCreated(long disputesCreated) {
        this.disputesCreated = disputesCreated;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public Map<String, Object> getStats() {
        return stats;
    }

    public void setStats(Map<String, Object> stats) {
        this.stats = stats;
    }
}
