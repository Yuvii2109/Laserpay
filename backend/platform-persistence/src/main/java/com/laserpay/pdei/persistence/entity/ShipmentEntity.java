package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Shipment ({@code SHP-} ids) - source of SHIPPING_RECORD evidence. */
@Entity
@Table(name = "shipments", schema = PdeiSchema.NAME)
public class ShipmentEntity extends VersionedEntity {

    @Id
    @Column(name = "shipment_id", nullable = false, length = 64)
    private String id;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "carrier", length = 64)
    private String carrier;

    @Column(name = "tracking_number", length = 128)
    private String trackingNumber;

    @Column(name = "service_level", length = 64)
    private String serviceLevel;

    /** CREATED|DISPATCHED|IN_TRANSIT|DELIVERED|RETURNED|LOST. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "declared_value_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "declared_value_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable declaredValue;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "estimated_delivery_at")
    private Instant estimatedDeliveryAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "destination_address", columnDefinition = "jsonb")
    private Map<String, Object> destinationAddress;

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

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public String getServiceLevel() {
        return serviceLevel;
    }

    public void setServiceLevel(String serviceLevel) {
        this.serviceLevel = serviceLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public MoneyEmbeddable getDeclaredValue() {
        return declaredValue;
    }

    public void setDeclaredValue(MoneyEmbeddable declaredValue) {
        this.declaredValue = declaredValue;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(Instant shippedAt) {
        this.shippedAt = shippedAt;
    }

    public Instant getEstimatedDeliveryAt() {
        return estimatedDeliveryAt;
    }

    public void setEstimatedDeliveryAt(Instant estimatedDeliveryAt) {
        this.estimatedDeliveryAt = estimatedDeliveryAt;
    }

    public Map<String, Object> getDestinationAddress() {
        return destinationAddress;
    }

    public void setDestinationAddress(Map<String, Object> destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
