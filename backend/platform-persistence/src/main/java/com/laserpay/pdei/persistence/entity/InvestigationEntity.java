package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable record of one investigation ({@code INV-} ids): the AI proposal (or the deterministic
 * short-circuit) plus the verdict the safety gate reached about it.
 *
 * <p>Nothing written here changes financial state - rule 2. The row exists so a human can see
 * exactly what the model claimed, which evidence it cited, what was rejected and why.
 *
 * <p>{@code confidenceBps} mirrors {@code InvestigationResult.confidence} (0.973 -> 9730).
 */
@Entity
@Table(name = "investigations", schema = PdeiSchema.NAME)
public class InvestigationEntity extends VersionedEntity {

    @Id
    @Column(name = "investigation_id", nullable = false, length = 64)
    private String id;

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "dispute_id", length = 64)
    private String disputeId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 32)
    private InvestigationClassification classification;

    @Column(name = "confidence_bps")
    private Integer confidenceBps;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_action", length = 48)
    private RecommendedAction recommendedAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "safety_decision", length = 32)
    private SafetyDecision safetyDecision;

    /** TRUE when no model was called (contract 9.4 deterministic short-circuit). */
    @Column(name = "deterministic", nullable = false)
    private boolean deterministic;

    @Column(name = "reasoning_summary", columnDefinition = "text")
    private String reasoningSummary;

    @Column(name = "narrative", columnDefinition = "text")
    private String narrative;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_evidence", columnDefinition = "jsonb")
    private List<String> supportingEvidence;

    /** Evidence TYPES the reasoner says are missing - types, not ids (contract 9.2). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_evidence", columnDefinition = "jsonb")
    private List<EvidenceType> missingEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contradictions", columnDefinition = "jsonb")
    private List<Map<String, Object>> contradictions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb")
    private List<Map<String, Object>> citations;

    /** Why the validator rejected the result, if it did (contract 9.3 rule numbers). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejection_reasons", columnDefinition = "jsonb")
    private List<String> rejectionReasons;

    /** The exact InvestigationContext sent to the reasoner: makes the run reproducible. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> contextSnapshot;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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

    public InvestigationClassification getClassification() {
        return classification;
    }

    public void setClassification(InvestigationClassification classification) {
        this.classification = classification;
    }

    public Integer getConfidenceBps() {
        return confidenceBps;
    }

    public void setConfidenceBps(Integer confidenceBps) {
        this.confidenceBps = confidenceBps;
    }

    public RecommendedAction getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(RecommendedAction recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public SafetyDecision getSafetyDecision() {
        return safetyDecision;
    }

    public void setSafetyDecision(SafetyDecision safetyDecision) {
        this.safetyDecision = safetyDecision;
    }

    public boolean isDeterministic() {
        return deterministic;
    }

    public void setDeterministic(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public String getReasoningSummary() {
        return reasoningSummary;
    }

    public void setReasoningSummary(String reasoningSummary) {
        this.reasoningSummary = reasoningSummary;
    }

    public String getNarrative() {
        return narrative;
    }

    public void setNarrative(String narrative) {
        this.narrative = narrative;
    }

    public List<String> getSupportingEvidence() {
        return supportingEvidence;
    }

    public void setSupportingEvidence(List<String> supportingEvidence) {
        this.supportingEvidence = supportingEvidence;
    }

    public List<EvidenceType> getMissingEvidence() {
        return missingEvidence;
    }

    public void setMissingEvidence(List<EvidenceType> missingEvidence) {
        this.missingEvidence = missingEvidence;
    }

    public List<Map<String, Object>> getContradictions() {
        return contradictions;
    }

    public void setContradictions(List<Map<String, Object>> contradictions) {
        this.contradictions = contradictions;
    }

    public List<Map<String, Object>> getCitations() {
        return citations;
    }

    public void setCitations(List<Map<String, Object>> citations) {
        this.citations = citations;
    }

    public List<String> getRejectionReasons() {
        return rejectionReasons;
    }

    public void setRejectionReasons(List<String> rejectionReasons) {
        this.rejectionReasons = rejectionReasons;
    }

    public Map<String, Object> getContextSnapshot() {
        return contextSnapshot;
    }

    public void setContextSnapshot(Map<String, Object> contextSnapshot) {
        this.contextSnapshot = contextSnapshot;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
