package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.event.EventSource;
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
 * External dispute / chargeback ({@code DSP-} ids). PDEI's whole point is that the evidence is
 * already assembled before this row appears; when it does, a {@link DisputeCaseEntity} workflow
 * is started for it.
 */
@Entity
@Table(name = "disputes", schema = PdeiSchema.NAME)
public class DisputeEntity extends VersionedEntity {

    @Id
    @Column(name = "dispute_id", nullable = false, length = 64)
    private String id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "psp_dispute_ref", length = 128)
    private String pspDisputeRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 48)
    private DisputeReasonCode reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DisputeStatus status;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable amount;

    @Column(name = "network", length = 32)
    private String network;

    /** RETRIEVAL|CHARGEBACK|PRE_ARBITRATION|ARBITRATION. */
    @Column(name = "stage", length = 32)
    private String stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private EventSource source = EventSource.PSP_ADAPTER;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** Representment deadline: drives admission-control urgency and the Temporal timers. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** WON|LOST|WITHDRAWN|EXPIRED. */
    @Column(name = "outcome", length = 32)
    private String outcome;

    @Column(name = "last_event_id", length = 64)
    private String lastEventId;

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

    public String getPspDisputeRef() {
        return pspDisputeRef;
    }

    public void setPspDisputeRef(String pspDisputeRef) {
        this.pspDisputeRef = pspDisputeRef;
    }

    public DisputeReasonCode getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(DisputeReasonCode reasonCode) {
        this.reasonCode = reasonCode;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public void setStatus(DisputeStatus status) {
        this.status = status;
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

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public EventSource getSource() {
        return source;
    }

    public void setSource(EventSource source) {
        this.source = source;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(String lastEventId) {
        this.lastEventId = lastEventId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
