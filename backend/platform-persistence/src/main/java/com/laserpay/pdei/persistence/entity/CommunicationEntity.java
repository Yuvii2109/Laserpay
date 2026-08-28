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
 * Customer communication ({@code COM-} ids) - becomes CUSTOMER_COMMUNICATION evidence.
 *
 * <p>The raw artifact (.eml, chat transcript) lives in MinIO under {@code objectKey}; the body
 * kept here is the searchable projection. V10 adds a maintained {@code search_vector} column,
 * which is not mapped as a JPA attribute (the database owns it).
 */
@Entity
@Table(name = "communications", schema = PdeiSchema.NAME)
public class CommunicationEntity extends VersionedEntity {

    @Id
    @Column(name = "communication_id", nullable = false, length = 64)
    private String id;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    /** EMAIL|SMS|CHAT|PHONE|PORTAL|WHATSAPP. */
    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    /** INBOUND|OUTBOUND. */
    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "subject", length = 512)
    private String subject;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "sender", length = 256)
    private String sender;

    @Column(name = "recipient", length = 256)
    private String recipient;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "sha256", length = 64)
    private String sha256;

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

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
