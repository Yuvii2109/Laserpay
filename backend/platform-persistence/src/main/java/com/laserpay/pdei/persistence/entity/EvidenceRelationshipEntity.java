package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A directed edge of the evidence graph (rendered by {@code GET /transactions/{id}/graph}).
 *
 * <p>CONTRADICTS edges are what the readiness engine penalises and what the safety gate counts
 * against {@code policy.maxContradictions}; SUPERSEDES edges drive lineage.
 */
@Entity
@Table(name = "evidence_relationships", schema = PdeiSchema.NAME)
public class EvidenceRelationshipEntity extends VersionedEntity {

    /** SUPERSEDES|SUPPORTS|CONTRADICTS|DERIVED_FROM|REFERENCES|DUPLICATE_OF|ATTACHED_TO. */
    public static final String SUPERSEDES = "SUPERSEDES";
    public static final String SUPPORTS = "SUPPORTS";
    public static final String CONTRADICTS = "CONTRADICTS";
    public static final String DERIVED_FROM = "DERIVED_FROM";
    public static final String REFERENCES = "REFERENCES";
    public static final String DUPLICATE_OF = "DUPLICATE_OF";
    public static final String ATTACHED_TO = "ATTACHED_TO";

    @Id
    @Column(name = "relationship_id", nullable = false, length = 64)
    private String id;

    @Column(name = "from_evidence_id", nullable = false, length = 64)
    private String fromEvidenceId;

    @Column(name = "to_evidence_id", nullable = false, length = 64)
    private String toEvidenceId;

    @Column(name = "relationship_type", nullable = false, length = 32)
    private String relationshipType;

    /** Detector confidence in basis points (0..10000). Integer, never a float. */
    @Column(name = "confidence_bps")
    private Integer confidenceBps;

    /** DETERMINISTIC|AI|MERCHANT|SYSTEM — who asserted the edge. */
    @Column(name = "detected_by", nullable = false, length = 32)
    private String detectedBy = "DETERMINISTIC";

    /** Conflicting field name for CONTRADICTS edges, e.g. {@code deliveredAt}. */
    @Column(name = "field", length = 128)
    private String field;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

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

    public String getFromEvidenceId() {
        return fromEvidenceId;
    }

    public void setFromEvidenceId(String fromEvidenceId) {
        this.fromEvidenceId = fromEvidenceId;
    }

    public String getToEvidenceId() {
        return toEvidenceId;
    }

    public void setToEvidenceId(String toEvidenceId) {
        this.toEvidenceId = toEvidenceId;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = relationshipType;
    }

    public Integer getConfidenceBps() {
        return confidenceBps;
    }

    public void setConfidenceBps(Integer confidenceBps) {
        this.confidenceBps = confidenceBps;
    }

    public String getDetectedBy() {
        return detectedBy;
    }

    public void setDetectedBy(String detectedBy) {
        this.detectedBy = detectedBy;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
