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
 * Evidence attached to a case, pinned to an exact evidence version.
 *
 * <p>{@code sha256AtAttach} is the hash observed when the item was attached: if the artifact's
 * hash later differs, the package is provably stale or the object was tampered with. This is
 * what makes a representment package reproducible.
 */
@Entity
@Table(name = "case_evidence", schema = PdeiSchema.NAME)
public class CaseEvidenceEntity extends VersionedEntity {

    /** PRIMARY|SUPPORTING|CONTEXT|REBUTTAL|EXCLUDED. */
    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_SUPPORTING = "SUPPORTING";
    public static final String ROLE_CONTEXT = "CONTEXT";
    public static final String ROLE_REBUTTAL = "REBUTTAL";
    public static final String ROLE_EXCLUDED = "EXCLUDED";

    @Id
    @Column(name = "case_evidence_id", nullable = false, length = 64)
    private String id;

    @Column(name = "case_id", nullable = false, length = 64)
    private String caseId;

    @Column(name = "evidence_id", nullable = false, length = 64)
    private String evidenceId;

    @Column(name = "evidence_version", nullable = false)
    private int evidenceVersion;

    @Column(name = "role", nullable = false, length = 32)
    private String role = ROLE_SUPPORTING;

    @Column(name = "sha256_at_attach", length = 64)
    private String sha256AtAttach;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "included_in_package", nullable = false)
    private boolean includedInPackage = true;

    @Column(name = "attached_at", nullable = false)
    private Instant attachedAt;

    @Column(name = "attached_by", length = 128)
    private String attachedBy;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

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

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public int getEvidenceVersion() {
        return evidenceVersion;
    }

    public void setEvidenceVersion(int evidenceVersion) {
        this.evidenceVersion = evidenceVersion;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getSha256AtAttach() {
        return sha256AtAttach;
    }

    public void setSha256AtAttach(String sha256AtAttach) {
        this.sha256AtAttach = sha256AtAttach;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isIncludedInPackage() {
        return includedInPackage;
    }

    public void setIncludedInPackage(boolean includedInPackage) {
        this.includedInPackage = includedInPackage;
    }

    public Instant getAttachedAt() {
        return attachedAt;
    }

    public void setAttachedAt(Instant attachedAt) {
        this.attachedAt = attachedAt;
    }

    public String getAttachedBy() {
        return attachedBy;
    }

    public void setAttachedBy(String attachedBy) {
        this.attachedBy = attachedBy;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
