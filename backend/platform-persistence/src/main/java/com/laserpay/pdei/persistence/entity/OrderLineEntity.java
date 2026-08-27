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
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single line of an order. Id convention {@code {orderId}-L{lineNumber}} — the contract
 * defines no dedicated prefix for order lines, so the parent id is reused deterministically.
 *
 * <p>{@code digitalGood} matters for readiness: a digital line cannot produce DELIVERY_PROOF,
 * so the requirement profile differs.
 */
@Entity
@Table(name = "order_lines", schema = PdeiSchema.NAME)
public class OrderLineEntity extends VersionedEntity {

    @Id
    @Column(name = "order_line_id", nullable = false, length = 64)
    private String id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "sku", length = 128)
    private String sku;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "unit_price_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "unit_price_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable unitPrice;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "line_total_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "line_total_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable lineTotal;

    @Column(name = "digital_good", nullable = false)
    private boolean digitalGood;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Deterministic id for a line, so replaying the same order event is idempotent. */
    public static String idFor(String orderId, int lineNumber) {
        return orderId + "-L" + lineNumber;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public MoneyEmbeddable getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(MoneyEmbeddable unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Money getUnitPriceAsMoney() {
        return MoneyEmbeddable.toMoney(unitPrice);
    }

    public void setUnitPriceFromMoney(Money money) {
        this.unitPrice = MoneyEmbeddable.ofNullable(money);
    }

    public MoneyEmbeddable getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(MoneyEmbeddable lineTotal) {
        this.lineTotal = lineTotal;
    }

    public Money getLineTotalAsMoney() {
        return MoneyEmbeddable.toMoney(lineTotal);
    }

    public void setLineTotalFromMoney(Money money) {
        this.lineTotal = MoneyEmbeddable.ofNullable(money);
    }

    public boolean isDigitalGood() {
        return digitalGood;
    }

    public void setDigitalGood(boolean digitalGood) {
        this.digitalGood = digitalGood;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
