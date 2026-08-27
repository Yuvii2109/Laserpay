package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.EvidenceType;
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
 * One claim made by an investigation, with the deterministic validation outcome.
 *
 * <p>{@code validated = false} plus a {@code validationError} is an unsupported claim: the
 * evidence id does not exist, is not linked to this transaction, or is a prohibited type. Those
 * rows are what {@code pdei_ai_unsupported_claims_total} counts, and any of them forces
 * {@link com.laserpay.pdei.common.domain.SafetyDecision#DENY}.
 */
@Entity
@Table(name = "investigation_findings", schema = PdeiSchema.NAME)
public class InvestigationFindingEntity extends VersionedEntity {

    /** SUPPORTING_EVIDENCE|MISSING_EVIDENCE|CONTRADICTION|CITATION|POLICY_VIOLATION|OBSERVATION. */
    public static final String TYPE_SUPPORTING_EVIDENCE = "SUPPORTING_EVIDENCE";
    public static final String TYPE_MISSING_EVIDENCE = "MISSING_EVIDENCE";
    public static final String TYPE_CONTRADICTION = "CONTRADICTION";
    public static final String TYPE_CITATION = "CITATION";
    public static final String TYPE_POLICY_VIOLATION = "POLICY_VIOLATION";
    public static final String TYPE_OBSERVATION = "OBSERVATION";

    @Id
    @Column(name = "finding_id", nullable = false, length = 64)
    private String id;

    @Column(name = "investigation_id", nullable = false, length = 64)
    private String investigationId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "finding_type", nullable = false, length = 32)
    private String findingType;

    @Column(name = "evidence_id", length = 64)
    private String evidenceId;

    @Column(name = "related_evidence_id", length = 64)
    private String relatedEvidenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", length = 48)
    private EvidenceType evidenceType;

    /** Conflicting field for CONTRADICTION findings, e.g. {@code deliveredAt}. */
    @Column(name = "field", length = 128)
    private String field;

    @Column(name = "claim", columnDefinition = "text")
    private String claim;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "validated", nullable = false)
    private boolean validated;

    @Column(name = "validation_error", length = 256)
    private String validationError;

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

    public String getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(String investigationId) {
        this.investigationId = investigationId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(int sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getFindingType() {
        return findingType;
    }

    public void setFindingType(String findingType) {
        this.findingType = findingType;
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

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getClaim() {
        return claim;
    }

    public void setClaim(String claim) {
        this.claim = claim;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public boolean isValidated() {
        return validated;
    }

    public void setValidated(boolean validated) {
        this.validated = validated;
    }

    public String getValidationError() {
        return validationError;
    }

    public void setValidationError(String validationError) {
        this.validationError = validationError;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
