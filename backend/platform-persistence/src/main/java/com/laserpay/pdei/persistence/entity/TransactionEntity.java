package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.ReadinessBand;
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
 * The unit of evidence readiness ({@code TX-} ids): one purchase, with its payments, order,
 * shipments, refunds and communications hanging off it.
 *
 * <p>{@code readinessScore}/{@code readinessBand} are a denormalised projection maintained by
 * readiness-worker so the control tower can filter by band without a join; the authoritative
 * history lives in {@code pdei.readiness_snapshots}.
 */
@Entity
@Table(name = "transactions", schema = PdeiSchema.NAME)
public class TransactionEntity extends VersionedEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, length = 64)
    private String id;

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
        @AttributeOverride(name = "amountMinor", column = @Column(name = "captured_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "captured_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable capturedAmount;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "refunded_amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "refunded_currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable refundedAmount;

    /** CREATED|AUTHORIZED|CAPTURED|SETTLED|PARTIALLY_REFUNDED|REFUNDED|FAILED|CHARGEBACK. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** ONLINE|POS|MOTO|RECURRING|IN_APP. */
    @Column(name = "channel", length = 32)
    private String channel;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "readiness_score")
    private Integer readinessScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "readiness_band", length = 32)
    private ReadinessBand readinessBand;

    @Column(name = "readiness_computed_at")
    private Instant readinessComputedAt;

    @Column(name = "last_event_id", length = 64)
    private String lastEventId;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

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

    /** Convenience view of the transaction value as the shared {@link Money} record. */
    public Money getAmountAsMoney() {
        return MoneyEmbeddable.toMoney(amount);
    }

    public void setAmountFromMoney(Money money) {
        this.amount = MoneyEmbeddable.ofNullable(money);
    }

    public MoneyEmbeddable getCapturedAmount() {
        return capturedAmount;
    }

    public void setCapturedAmount(MoneyEmbeddable capturedAmount) {
        this.capturedAmount = capturedAmount;
    }

    public Money getCapturedAmountAsMoney() {
        return MoneyEmbeddable.toMoney(capturedAmount);
    }

    public void setCapturedAmountFromMoney(Money money) {
        this.capturedAmount = MoneyEmbeddable.ofNullable(money);
    }

    public MoneyEmbeddable getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(MoneyEmbeddable refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public Money getRefundedAmountAsMoney() {
        return MoneyEmbeddable.toMoney(refundedAmount);
    }

    public void setRefundedAmountFromMoney(Money money) {
        this.refundedAmount = MoneyEmbeddable.ofNullable(money);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public Integer getReadinessScore() {
        return readinessScore;
    }

    public void setReadinessScore(Integer readinessScore) {
        this.readinessScore = readinessScore;
    }

    public ReadinessBand getReadinessBand() {
        return readinessBand;
    }

    public void setReadinessBand(ReadinessBand readinessBand) {
        this.readinessBand = readinessBand;
    }

    public Instant getReadinessComputedAt() {
        return readinessComputedAt;
    }

    public void setReadinessComputedAt(Instant readinessComputedAt) {
        this.readinessComputedAt = readinessComputedAt;
    }

    public String getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(String lastEventId) {
        this.lastEventId = lastEventId;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
