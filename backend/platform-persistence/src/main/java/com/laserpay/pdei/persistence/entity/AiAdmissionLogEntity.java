package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only ledger of admission-control decisions (contract 9.4): who was sent to the model,
 * who was not, and exactly why.
 *
 * <pre>
 * priority = 0.40*financialImpact + 0.25*deadlineUrgency
 *          + 0.20*ambiguity       + 0.15*(1 - deterministicConfidence)
 * admit if priority >= 55 AND token bucket allows AND deterministic path unresolved
 * </pre>
 *
 * <p>All four components and the final priority are stored as 0..100 integers so the decision
 * can be re-derived exactly. This table backs {@code pdei_ai_admission_total{decision}} and the
 * funnel metric events -> candidates -> ambiguous -> AI -> human.
 */
@Entity
@Immutable
@Table(name = "ai_admission_log", schema = PdeiSchema.NAME)
public class AiAdmissionLogEntity {

    /** Deterministic short-circuit labels (CHECK-constrained). */
    public static final String SHORT_CIRCUIT_ALL_MANDATORY_SATISFIED = "ALL_MANDATORY_SATISFIED";
    public static final String SHORT_CIRCUIT_NO_EVIDENCE = "NO_EVIDENCE";
    public static final String SHORT_CIRCUIT_PAST_DEADLINE = "PAST_DEADLINE";
    public static final String SHORT_CIRCUIT_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String SHORT_CIRCUIT_RATE_LIMITED = "RATE_LIMITED";
    public static final String SHORT_CIRCUIT_BELOW_PRIORITY_THRESHOLD = "BELOW_PRIORITY_THRESHOLD";

    @Id
    @Column(name = "admission_id", nullable = false, length = 64)
    private String id;

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "dispute_id", length = 64)
    private String disputeId;

    @Column(name = "investigation_id", length = 64)
    private String investigationId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "admitted", nullable = false)
    private boolean admitted;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "financial_impact_component", nullable = false)
    private int financialImpactComponent;

    @Column(name = "deadline_urgency_component", nullable = false)
    private int deadlineUrgencyComponent;

    @Column(name = "ambiguity_component", nullable = false)
    private int ambiguityComponent;

    @Column(name = "deterministic_confidence_component", nullable = false)
    private int deterministicConfidenceComponent;

    /** Disputed amount at decision time (drives the financial impact component). */
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "amount_minor")),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable amount;

    @Column(name = "short_circuit", length = 64)
    private String shortCircuit;

    @Column(name = "rate_limited", nullable = false)
    private boolean rateLimited;

    /** Redis budget key that was consulted, e.g. {@code pdei:ai:budget:2026-08-26}. */
    @Column(name = "budget_key", length = 64)
    private String budgetKey;

    @Column(name = "budget_remaining")
    private Integer budgetRemaining;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (decidedAt == null) {
            decidedAt = now;
        }
    }

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

    public String getDisputeId() {
        return disputeId;
    }

    public void setDisputeId(String disputeId) {
        this.disputeId = disputeId;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(String investigationId) {
        this.investigationId = investigationId;
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

    public boolean isAdmitted() {
        return admitted;
    }

    public void setAdmitted(boolean admitted) {
        this.admitted = admitted;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getFinancialImpactComponent() {
        return financialImpactComponent;
    }

    public void setFinancialImpactComponent(int financialImpactComponent) {
        this.financialImpactComponent = financialImpactComponent;
    }

    public int getDeadlineUrgencyComponent() {
        return deadlineUrgencyComponent;
    }

    public void setDeadlineUrgencyComponent(int deadlineUrgencyComponent) {
        this.deadlineUrgencyComponent = deadlineUrgencyComponent;
    }

    public int getAmbiguityComponent() {
        return ambiguityComponent;
    }

    public void setAmbiguityComponent(int ambiguityComponent) {
        this.ambiguityComponent = ambiguityComponent;
    }

    public int getDeterministicConfidenceComponent() {
        return deterministicConfidenceComponent;
    }

    public void setDeterministicConfidenceComponent(int deterministicConfidenceComponent) {
        this.deterministicConfidenceComponent = deterministicConfidenceComponent;
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

    public String getShortCircuit() {
        return shortCircuit;
    }

    public void setShortCircuit(String shortCircuit) {
        this.shortCircuit = shortCircuit;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    public String getBudgetKey() {
        return budgetKey;
    }

    public void setBudgetKey(String budgetKey) {
        this.budgetKey = budgetKey;
    }

    public Integer getBudgetRemaining() {
        return budgetRemaining;
    }

    public void setBudgetRemaining(Integer budgetRemaining) {
        this.budgetRemaining = budgetRemaining;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AiAdmissionLogEntity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AiAdmissionLogEntity[" + id + ", admitted=" + admitted + ", priority=" + priority + "]";
    }
}
