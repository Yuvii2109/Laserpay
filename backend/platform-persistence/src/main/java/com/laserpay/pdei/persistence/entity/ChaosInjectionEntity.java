package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.ChaosType;
import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A deliberately injected failure (duplicate event, corrupted hash, killed worker, ...).
 *
 * <p>Recording injections is what lets a demo prove idempotency, replayability and evidence
 * integrity instead of merely claiming them: every injection has a timestamp, a target and a
 * recorded outcome, and drives {@code pdei_chaos_injections_total{type}}.
 */
@Entity
@Table(name = "chaos_injections", schema = PdeiSchema.NAME)
public class ChaosInjectionEntity extends VersionedEntity {

    /** REQUESTED|APPLIED|FAILED|REVERTED. */
    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REVERTED = "REVERTED";

    @Id
    @Column(name = "injection_id", nullable = false, length = 64)
    private String id;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private ChaosType type;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_REQUESTED;

    /** Free-form target selector, e.g. {@code {"transactionId":"TX-1","evidenceId":"EV-9"}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target", columnDefinition = "jsonb")
    private Map<String, Object> target;

    @Column(name = "delay_ms")
    private Long delayMs;

    /** How many events the injection applies to (DUPLICATE_EVENT, REPLAY_EVENTS, ...). */
    @Column(name = "event_count")
    private Integer eventCount;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "injected_at", nullable = false)
    private Instant injectedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public ChaosType getType() {
        return type;
    }

    public void setType(ChaosType type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getTarget() {
        return target;
    }

    public void setTarget(Map<String, Object> target) {
        this.target = target;
    }

    public Long getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(Long delayMs) {
        this.delayMs = delayMs;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public void setEventCount(Integer eventCount) {
        this.eventCount = eventCount;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public Instant getInjectedAt() {
        return injectedAt;
    }

    public void setInjectedAt(Instant injectedAt) {
        this.injectedAt = injectedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
