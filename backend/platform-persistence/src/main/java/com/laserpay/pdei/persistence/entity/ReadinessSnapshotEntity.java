package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One deterministic readiness computation for a transaction (contract section 7).
 *
 * <p>Snapshots are appended, never edited: the previous snapshot for the same
 * (transaction, reasonCode) is flagged {@code isCurrent = false}. Keeping the score
 * decomposition ({@code baseScore}, {@code penaltyTotal}, the weight counters) means the UI can
 * explain a score without recomputing it, which is the whole point of a deterministic engine.
 */
@Entity
@Table(name = "readiness_snapshots", schema = PdeiSchema.NAME)
public class ReadinessSnapshotEntity extends VersionedEntity {

    /** Allowed values of {@code triggerReason} (CHECK-constrained). */
    public static final String TRIGGER_EVIDENCE_EVENT = "EVIDENCE_EVENT";
    public static final String TRIGGER_ENTITY_STATE_CHANGE = "ENTITY_STATE_CHANGE";
    public static final String TRIGGER_POLICY_VERSION_CHANGE = "POLICY_VERSION_CHANGE";
    public static final String TRIGGER_NIGHTLY_SWEEP = "NIGHTLY_SWEEP";
    public static final String TRIGGER_MANUAL_RECOMPUTE = "MANUAL_RECOMPUTE";
    public static final String TRIGGER_DISPUTE_EVENT = "DISPUTE_EVENT";

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String id;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** {@code null} = computed against the merchant's baseline requirement profile. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 48)
    private DisputeReasonCode reasonCode;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false, length = 32)
    private ReadinessBand band;

    @Column(name = "base_score", nullable = false)
    private int baseScore;

    @Column(name = "penalty_total", nullable = false)
    private int penaltyTotal;

    @Column(name = "satisfied_weight", nullable = false)
    private int satisfiedWeight;

    @Column(name = "total_weight", nullable = false)
    private int totalWeight;

    @Column(name = "mandatory_total", nullable = false)
    private int mandatoryTotal;

    @Column(name = "mandatory_satisfied", nullable = false)
    private int mandatorySatisfied;

    @Column(name = "recommended_total", nullable = false)
    private int recommendedTotal;

    @Column(name = "recommended_satisfied", nullable = false)
    private int recommendedSatisfied;

    @Column(name = "gap_count", nullable = false)
    private int gapCount;

    @Column(name = "contradiction_count", nullable = false)
    private int contradictionCount;

    /** Serialized {@code List<RequirementView>} exactly as the REST layer returns it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requirements", columnDefinition = "jsonb")
    private List<Map<String, Object>> requirements;

    @Column(name = "policy_id", length = 64)
    private String policyId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "trigger_event_id", length = 64)
    private String triggerEventId;

    @Column(name = "trigger_reason", length = 64)
    private String triggerReason;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public DisputeReasonCode getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(DisputeReasonCode reasonCode) {
        this.reasonCode = reasonCode;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public ReadinessBand getBand() {
        return band;
    }

    public void setBand(ReadinessBand band) {
        this.band = band;
    }

    public int getBaseScore() {
        return baseScore;
    }

    public void setBaseScore(int baseScore) {
        this.baseScore = baseScore;
    }

    public int getPenaltyTotal() {
        return penaltyTotal;
    }

    public void setPenaltyTotal(int penaltyTotal) {
        this.penaltyTotal = penaltyTotal;
    }

    public int getSatisfiedWeight() {
        return satisfiedWeight;
    }

    public void setSatisfiedWeight(int satisfiedWeight) {
        this.satisfiedWeight = satisfiedWeight;
    }

    public int getTotalWeight() {
        return totalWeight;
    }

    public void setTotalWeight(int totalWeight) {
        this.totalWeight = totalWeight;
    }

    public int getMandatoryTotal() {
        return mandatoryTotal;
    }

    public void setMandatoryTotal(int mandatoryTotal) {
        this.mandatoryTotal = mandatoryTotal;
    }

    public int getMandatorySatisfied() {
        return mandatorySatisfied;
    }

    public void setMandatorySatisfied(int mandatorySatisfied) {
        this.mandatorySatisfied = mandatorySatisfied;
    }

    public int getRecommendedTotal() {
        return recommendedTotal;
    }

    public void setRecommendedTotal(int recommendedTotal) {
        this.recommendedTotal = recommendedTotal;
    }

    public int getRecommendedSatisfied() {
        return recommendedSatisfied;
    }

    public void setRecommendedSatisfied(int recommendedSatisfied) {
        this.recommendedSatisfied = recommendedSatisfied;
    }

    public int getGapCount() {
        return gapCount;
    }

    public void setGapCount(int gapCount) {
        this.gapCount = gapCount;
    }

    public int getContradictionCount() {
        return contradictionCount;
    }

    public void setContradictionCount(int contradictionCount) {
        this.contradictionCount = contradictionCount;
    }

    public List<Map<String, Object>> getRequirements() {
        return requirements;
    }

    public void setRequirements(List<Map<String, Object>> requirements) {
        this.requirements = requirements;
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(Integer policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getTriggerEventId() {
        return triggerEventId;
    }

    public void setTriggerEventId(String triggerEventId) {
        this.triggerEventId = triggerEventId;
    }

    public String getTriggerReason() {
        return triggerReason;
    }

    public void setTriggerReason(String triggerReason) {
        this.triggerReason = triggerReason;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
