package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.money.Money;
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

/** Merchant order ({@code ORD-} ids) — the ORDER_RECORD evidence source. */
@Entity
@Table(name = "orders", schema = PdeiSchema.NAME)
public class OrderEntity extends VersionedEntity {

    @Id
    @Column(name = "order_id", nullable = false, length = 64)
    private String id;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "external_ref", length = 128)
    private String externalRef;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable amount;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "tax_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "tax_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable taxAmount;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "shipping_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "shipping_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable shippingAmount;

    /** CREATED|CONFIRMED|PARTIALLY_FULFILLED|FULFILLED|CANCELLED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", columnDefinition = "jsonb")
    private Map<String, Object> shippingAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_address", columnDefinition = "jsonb")
    private Map<String, Object> billingAddress;

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

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
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

    public MoneyEmbeddable getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(MoneyEmbeddable taxAmount) {
        this.taxAmount = taxAmount;
    }

    public MoneyEmbeddable getShippingAmount() {
        return shippingAmount;
    }

    public void setShippingAmount(MoneyEmbeddable shippingAmount) {
        this.shippingAmount = shippingAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(Instant placedAt) {
        this.placedAt = placedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public void setFulfilledAt(Instant fulfilledAt) {
        this.fulfilledAt = fulfilledAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Map<String, Object> getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(Map<String, Object> shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    public Map<String, Object> getBillingAddress() {
        return billingAddress;
    }

    public void setBillingAddress(Map<String, Object> billingAddress) {
        this.billingAddress = billingAddress;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
