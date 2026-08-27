package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
 * Current state of one evidence artifact ({@code EV-} ids).
 *
 * <p>The bytes live in MinIO at {@code objectKey}; this row owns the metadata, the integrity
 * hash and the provenance. Every mutation appends a row to {@code evidence_versions} — history
 * is never overwritten (reference section 12).
 *
 * <p>The {@code search_vector} column added by V10 is deliberately NOT mapped: it is maintained
 * by a database trigger and queried through the native FTS query in {@code EvidenceRepository}.
 */
@Entity
@Table(name = "evidence", schema = PdeiSchema.NAME)
public class EvidenceEntity extends VersionedEntity {

    @Id
    @Column(name = "evidence_id", nullable = false, length = 64)
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "related_entity_type", length = 32)
    private AggregateType relatedEntityType;

    @Column(name = "related_entity_id", length = 64)
    private String relatedEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private EvidenceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EvidenceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private EvidenceSource source;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "filename", length = 256)
    private String filename;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    /** Text extracted by document-processor-service (Tika/PDFBox); feeds the FTS vector. */
    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    /** Optional monetary value asserted by the artifact (invoice total, refunded amount, ...). */
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "amount_minor")),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable amount;

    @Column(name = "source_event_id", length = 64)
    private String sourceEventId;

    /** When the evidenced fact happened (delivery time, invoice date). */
    @Column(name = "captured_at")
    private Instant capturedAt;

    /** When PDEI observed the artifact. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidated_reason", length = 512)
    private String invalidatedReason;

    /** Evidence id that replaced this one (set together with status SUPERSEDED). */
    @Column(name = "superseded_by", length = 64)
    private String supersededBy;

    @Column(name = "integrity_verified_at")
    private Instant integrityVerifiedAt;

    @Column(name = "integrity_ok")
    private Boolean integrityOk;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", columnDefinition = "jsonb")
    private Map<String, Object> provenance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** MinIO key convention from contract section 11. */
    public String buildObjectKey() {
        return merchantId + "/" + transactionId + "/" + type + "/" + id
                + "/v" + currentVersion + "/" + (filename == null ? "artifact" : filename);
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

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public AggregateType getRelatedEntityType() {
        return relatedEntityType;
    }

    public void setRelatedEntityType(AggregateType relatedEntityType) {
        this.relatedEntityType = relatedEntityType;
    }

    public String getRelatedEntityId() {
        return relatedEntityId;
    }

    public void setRelatedEntityId(String relatedEntityId) {
        this.relatedEntityId = relatedEntityId;
    }

    public EvidenceType getType() {
        return type;
    }

    public void setType(EvidenceType type) {
        this.type = type;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public void setStatus(EvidenceStatus status) {
        this.status = status;
    }

    public EvidenceSource getSource() {
        return source;
    }

    public void setSource(EvidenceSource source) {
        this.source = source;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public MoneyEmbeddable getAmount() {
        return amount;
    }

    public void setAmount(MoneyEmbeddable amount) {
        this.amount = amount;
    }

    public Money getAmountAsMoney() {
        return MoneyEmbeddable.toMoney(amount);
    }

    public void setAmountFromMoney(Money money) {
        this.amount = MoneyEmbeddable.ofNullable(money);
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(Instant invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public String getInvalidatedReason() {
        return invalidatedReason;
    }

    public void setInvalidatedReason(String invalidatedReason) {
        this.invalidatedReason = invalidatedReason;
    }

    public String getSupersededBy() {
        return supersededBy;
    }

    public void setSupersededBy(String supersededBy) {
        this.supersededBy = supersededBy;
    }

    public Instant getIntegrityVerifiedAt() {
        return integrityVerifiedAt;
    }

    public void setIntegrityVerifiedAt(Instant integrityVerifiedAt) {
        this.integrityVerifiedAt = integrityVerifiedAt;
    }

    public Boolean getIntegrityOk() {
        return integrityOk;
    }

    public void setIntegrityOk(Boolean integrityOk) {
        this.integrityOk = integrityOk;
    }

    public Map<String, Object> getProvenance() {
        return provenance;
    }

    public void setProvenance(Map<String, Object> provenance) {
        this.provenance = provenance;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
