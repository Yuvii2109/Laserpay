package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
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
 * Representment case ({@code CASE-} ids) - the durable projection of one
 * {@code DisputeCaseWorkflow} execution (Temporal workflow id {@code case-{caseId}}).
 *
 * <p>{@code safetyDecision} records what the deterministic gate decided about the AI proposal;
 * DENY routes the case to AWAITING_APPROVAL / human review and is always audited.
 */
@Entity
@Table(name = "dispute_cases", schema = PdeiSchema.NAME)
public class DisputeCaseEntity extends VersionedEntity {

    @Id
    @Column(name = "case_id", nullable = false, length = 64)
    private String id;

    @Column(name = "dispute_id", nullable = false, length = 64)
    private String disputeId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CaseStatus status;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amountMinor", column = @Column(name = "amount_minor", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)"))
    })
    private MoneyEmbeddable amount;

    @Column(name = "workflow_id", length = 128)
    private String workflowId;

    @Column(name = "run_id", length = 128)
    private String runId;

    @Column(name = "task_queue", nullable = false, length = 64)
    private String taskQueue = "pdei-dispute-cases";

    @Column(name = "assigned_to", length = 128)
    private String assignedTo;

    @Column(name = "readiness_score")
    private Integer readinessScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "readiness_band", length = 32)
    private ReadinessBand readinessBand;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_action", length = 48)
    private RecommendedAction recommendedAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "safety_decision", length = 32)
    private SafetyDecision safetyDecision;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "prepared_at")
    private Instant preparedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** MinIO key of the representment bundle in bucket {@code pdei-packages}. */
    @Column(name = "package_object_key", length = 512)
    private String packageObjectKey;

    @Column(name = "package_version")
    private Integer packageVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "package_manifest", columnDefinition = "jsonb")
    private Map<String, Object> packageManifest;

    @Column(name = "approval_actor", length = 128)
    private String approvalActor;

    @Column(name = "approval_at")
    private Instant approvalAt;

    @Column(name = "approval_notes", columnDefinition = "text")
    private String approvalNotes;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Temporal workflow id convention from contract section 10. */
    public static String workflowIdFor(String caseId) {
        return "case-" + caseId;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
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

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
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

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
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

    public Instant getPreparedAt() {
        return preparedAt;
    }

    public void setPreparedAt(Instant preparedAt) {
        this.preparedAt = preparedAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getPackageObjectKey() {
        return packageObjectKey;
    }

    public void setPackageObjectKey(String packageObjectKey) {
        this.packageObjectKey = packageObjectKey;
    }

    public Integer getPackageVersion() {
        return packageVersion;
    }

    public void setPackageVersion(Integer packageVersion) {
        this.packageVersion = packageVersion;
    }

    public Map<String, Object> getPackageManifest() {
        return packageManifest;
    }

    public void setPackageManifest(Map<String, Object> packageManifest) {
        this.packageManifest = packageManifest;
    }

    public String getApprovalActor() {
        return approvalActor;
    }

    public void setApprovalActor(String approvalActor) {
        this.approvalActor = approvalActor;
    }

    public Instant getApprovalAt() {
        return approvalAt;
    }

    public void setApprovalAt(Instant approvalAt) {
        this.approvalAt = approvalAt;
    }

    public String getApprovalNotes() {
        return approvalNotes;
    }

    public void setApprovalNotes(String approvalNotes) {
        this.approvalNotes = approvalNotes;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
