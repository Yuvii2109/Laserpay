package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
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
 * Head of a merchant policy ({@code POL-} ids). The immutable history lives in
 * {@code policy_versions}; {@code currentVersion} points at the active one.
 *
 * <p>The policy is what "disposes" after the AI "proposes": {@code autoPrepareMinConfidenceBps},
 * {@code maxContradictions}, {@code prohibitedEvidenceTypes} and {@code permittedActions} are
 * the deterministic gate inputs of contract section 9.3.
 */
@Entity
@Table(name = "policies", schema = PdeiSchema.NAME)
public class PolicyEntity extends VersionedEntity {

    @Id
    @Column(name = "policy_id", nullable = false, length = 64)
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** GLOBAL|MERCHANT|REASON_CODE. */
    @Column(name = "scope", nullable = false, length = 32)
    private String scope = "MERCHANT";

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 48)
    private DisputeReasonCode reasonCode;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    /** {@code policyConstraints.autoPrepareMinConfidence} in basis points (9000 = 0.90). */
    @Column(name = "auto_prepare_min_confidence_bps", nullable = false)
    private int autoPrepareMinConfidenceBps = 9000;

    @Column(name = "max_contradictions", nullable = false)
    private int maxContradictions;

    /** EvidenceType names that may never be cited. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prohibited_evidence_types", columnDefinition = "jsonb")
    private List<String> prohibitedEvidenceTypes;

    /** RecommendedAction names this policy permits without human review. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permitted_actions", columnDefinition = "jsonb")
    private List<String> permittedActions;

    @Column(name = "evidence_ttl_days")
    private Integer evidenceTtlDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Convenience for the safety gate, which compares against a 0..1 confidence. */
    public boolean allowsAutoPrepare(int confidenceBps) {
        return confidenceBps >= autoPrepareMinConfidenceBps;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public DisputeReasonCode getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(DisputeReasonCode reasonCode) {
        this.reasonCode = reasonCode;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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

    public Integer getEvidenceTtlDays() {
        return evidenceTtlDays;
    }

    public void setEvidenceTtlDays(Integer evidenceTtlDays) {
        this.evidenceTtlDays = evidenceTtlDays;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
