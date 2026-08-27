package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable snapshot of a policy at one version. {@code PUT /policies/{policyId}} appends a new
 * row rather than editing the previous one, so a readiness snapshot or a case decision can
 * always be replayed against the exact policy text that was in force.
 *
 * <p>Id convention {@code {policyId}-v{versionNumber}}.
 */
@Entity
@Immutable
@Table(name = "policy_versions", schema = PdeiSchema.NAME)
public class PolicyVersionEntity {

    @Id
    @Column(name = "policy_version_id", nullable = false, length = 64)
    private String id;

    @Column(name = "policy_id", nullable = false, length = 64)
    private String policyId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "parent_version")
    private Integer parentVersion;

    /** Full serialized policy document; the referee when reconstructing an old decision. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> document;

    @Column(name = "auto_prepare_min_confidence_bps", nullable = false)
    private int autoPrepareMinConfidenceBps;

    @Column(name = "max_contradictions", nullable = false)
    private int maxContradictions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prohibited_evidence_types", columnDefinition = "jsonb")
    private List<String> prohibitedEvidenceTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permitted_actions", columnDefinition = "jsonb")
    private List<String> permittedActions;

    /** sha256 of the canonical JSON of {@code document} (tamper evidence). */
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "change_reason", length = 512)
    private String changeReason;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static String idFor(String policyId, int versionNumber) {
        return policyId + "-v" + versionNumber;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (id == null && policyId != null) {
            id = idFor(policyId, versionNumber);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public Integer getParentVersion() {
        return parentVersion;
    }

    public void setParentVersion(Integer parentVersion) {
        this.parentVersion = parentVersion;
    }

    public Map<String, Object> getDocument() {
        return document;
    }

    public void setDocument(Map<String, Object> document) {
        this.document = document;
    }

    public int getAutoPrepareMinConfidenceBps() {
        return autoPrepareMinConfidenceBps;
    }

    public void setAutoPrepareMinConfidenceBps(int autoPrepareMinConfidenceBps) {
        this.autoPrepareMinConfidenceBps = autoPrepareMinConfidenceBps;
    }

    public int getMaxContradictions() {
        return maxContradictions;
    }

    public void setMaxContradictions(int maxContradictions) {
        this.maxContradictions = maxContradictions;
    }

    public List<String> getProhibitedEvidenceTypes() {
        return prohibitedEvidenceTypes;
    }

    public void setProhibitedEvidenceTypes(List<String> prohibitedEvidenceTypes) {
        this.prohibitedEvidenceTypes = prohibitedEvidenceTypes;
    }

    public List<String> getPermittedActions() {
        return permittedActions;
    }

    public void setPermittedActions(List<String> permittedActions) {
        this.permittedActions = permittedActions;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PolicyVersionEntity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "PolicyVersionEntity[" + id + "]";
    }
}
