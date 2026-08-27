package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One cell of the requirement matrix: "for this policy version and this reason code, evidence
 * of type X is MANDATORY/RECOMMENDED/OPTIONAL/PROHIBITED with weight W".
 *
 * <p>This is the input to the deterministic readiness formula of contract section 7. Default
 * weights come from {@link RequirementStrength#weight()} (3/2/1/0) but are stored per row so a
 * merchant can tune a specific requirement without changing the shared enum.
 */
@Entity
@Table(name = "evidence_requirements", schema = PdeiSchema.NAME)
public class EvidenceRequirementEntity extends VersionedEntity {

    @Id
    @Column(name = "requirement_id", nullable = false, length = 64)
    private String id;

    @Column(name = "policy_id", nullable = false, length = 64)
    private String policyId;

    @Column(name = "policy_version", nullable = false)
    private int policyVersion;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** {@code null} means the requirement belongs to the merchant's baseline profile. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 48)
    private DisputeReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 48)
    private EvidenceType evidenceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "strength", nullable = false, length = 16)
    private RequirementStrength strength;

    @Column(name = "weight", nullable = false)
    private int weight = 1;

    /** Evidence older than this is treated as EXPIRED for this requirement. */
    @Column(name = "max_age_days")
    private Integer maxAgeDays;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public boolean isMandatory() {
        return strength == RequirementStrength.MANDATORY;
    }

    @Override
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

    public int getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(int policyVersion) {
        this.policyVersion = policyVersion;
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

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public RequirementStrength getStrength() {
        return strength;
    }

    public void setStrength(RequirementStrength strength) {
        this.strength = strength;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public Integer getMaxAgeDays() {
        return maxAgeDays;
    }

    public void setMaxAgeDays(Integer maxAgeDays) {
        this.maxAgeDays = maxAgeDays;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
