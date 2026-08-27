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
 * Delivery attempt / outcome ({@code DLV-} ids) — the source of DELIVERY_PROOF evidence and
 * the single most decisive artifact for GOODS_NOT_RECEIVED disputes.
 *
 * <p>Coordinates are integer micro-degrees ({@code 12.9716 -> 12971600}): the schema contains
 * no floating point columns at all.
 */
@Entity
@Table(name = "deliveries", schema = PdeiSchema.NAME)
public class DeliveryEntity extends VersionedEntity {

    @Id
    @Column(name = "delivery_id", nullable = false, length = 64)
    private String id;

    @Column(name = "shipment_id", nullable = false, length = 64)
    private String shipmentId;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** PENDING|ATTEMPTED|DELIVERED|REFUSED|FAILED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "recipient_name", length = 256)
    private String recipientName;

    @Column(name = "signed_by", length = 256)
    private String signedBy;

    @Column(name = "signature_captured", nullable = false)
    private boolean signatureCaptured;

    @Column(name = "proof_object_key", length = 512)
    private String proofObjectKey;

    @Column(name = "geo_lat_micro")
    private Integer geoLatMicro;

    @Column(name = "geo_lon_micro")
    private Integer geoLonMicro;

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

    public String getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(String shipmentId) {
        this.shipmentId = shipmentId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getSignedBy() {
        return signedBy;
    }

    public void setSignedBy(String signedBy) {
        this.signedBy = signedBy;
    }

    public boolean isSignatureCaptured() {
        return signatureCaptured;
    }

    public void setSignatureCaptured(boolean signatureCaptured) {
        this.signatureCaptured = signatureCaptured;
    }

    public String getProofObjectKey() {
        return proofObjectKey;
    }

    public void setProofObjectKey(String proofObjectKey) {
        this.proofObjectKey = proofObjectKey;
    }

    public Integer getGeoLatMicro() {
        return geoLatMicro;
    }

    public void setGeoLatMicro(Integer geoLatMicro) {
        this.geoLatMicro = geoLatMicro;
    }

    public Integer getGeoLonMicro() {
        return geoLonMicro;
    }

    public void setGeoLonMicro(Integer geoLonMicro) {
        this.geoLonMicro = geoLonMicro;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
