package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.RequirementStrength;
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
 * A concrete reason a transaction is not representment-ready: missing, expired, expiring,
 * contradictory, unverifiable, low quality or version-conflicted evidence.
 *
 * <p>Unresolved gaps are the at-risk feed ({@code GET /gaps?merchantId&type&severity}) and the
 * pre-dispute product surface: the merchant fixes them BEFORE a dispute exists.
 * {@code penaltyApplied} records the exact score deduction this gap caused.
 */
@Entity
@Table(name = "readiness_gaps", schema = PdeiSchema.NAME)
public class ReadinessGapEntity extends VersionedEntity {

    @Id
    @Column(name = "gap_id", nullable = false, length = 64)
    private String id;

    @Column(name = "snapshot_id", length = 64)
    private String snapshotId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private GapType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private GapSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", length = 48)
    private EvidenceType evidenceType;

    @Column(name = "evidence_id", length = 64)
    private String evidenceId;

    /** The other side of a CONTRADICTORY / VERSION_CONFLICT pair. */
    @Column(name = "related_evidence_id", length = 64)
    private String relatedEvidenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_strength", length = 16)
    private RequirementStrength requirementStrength;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /** Human-actionable instruction shown in the at-risk feed. */
    @Column(name = "remediation", columnDefinition = "text")
    private String remediation;

    @Column(name = "penalty_applied", nullable = false)
    private int penaltyApplied;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
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

    public GapType getType() {
        return type;
    }

    public void setType(GapType type) {
        this.type = type;
    }

    public GapSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(GapSeverity severity) {
        this.severity = severity;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getRelatedEvidenceId() {
        return relatedEvidenceId;
    }

    public void setRelatedEvidenceId(String relatedEvidenceId) {
        this.relatedEvidenceId = relatedEvidenceId;
    }

    public RequirementStrength getRequirementStrength() {
        return requirementStrength;
    }

    public void setRequirementStrength(RequirementStrength requirementStrength) {
        this.requirementStrength = requirementStrength;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getRemediation() {
        return remediation;
    }

    public void setRemediation(String remediation) {
        this.remediation = remediation;
    }

    public int getPenaltyApplied() {
        return penaltyApplied;
    }

    public void setPenaltyApplied(int penaltyApplied) {
        this.penaltyApplied = penaltyApplied;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
