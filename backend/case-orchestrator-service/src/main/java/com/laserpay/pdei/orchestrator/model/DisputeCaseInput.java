package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.money.Money;

import java.time.Instant;

/**
 * The single argument of {@code DisputeCaseWorkflow.run}.
 *
 * <p>Everything the workflow needs in order to stay deterministic lives here: identities, the money
 * at stake (minor units and currency, never a decimal), the representment deadline, the pinned
 * {@link CaseTimers}, and - on a continue-as-new - the phase to resume from plus its
 * {@link CaseCarryOver}.</p>
 *
 * <p>{@code deadlineAt} is the network representment deadline copied from the dispute row. The
 * workflow compares it against {@code Workflow.currentTimeMillis()}, which is the replay-safe
 * workflow clock, never {@code Instant.now()}.</p>
 */
public record DisputeCaseInput(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        DisputeReasonCode reasonCode,
        Money disputeAmount,
        Instant openedAt,
        Instant deadlineAt,
        String correlationId,
        String sourceEventId,
        String actor,
        CaseTimers timers,
        CasePhase resumePhase,
        CaseCarryOver carryOver,
        int continuationCount) {

    /** Workflow id prefix mandated by PLATFORM-CONTRACT section 10. */
    public static final String WORKFLOW_ID_PREFIX = "case-";

    /** First execution of a case: no resume phase, no carry-over. */
    public static DisputeCaseInput start(String caseId, String disputeId, String merchantId,
                                         String transactionId, DisputeReasonCode reasonCode,
                                         Money disputeAmount, Instant openedAt, Instant deadlineAt,
                                         String correlationId, String sourceEventId, String actor,
                                         CaseTimers timers) {
        return new DisputeCaseInput(caseId, disputeId, merchantId, transactionId, reasonCode,
                disputeAmount, openedAt, deadlineAt, correlationId, sourceEventId, actor, timers,
                CasePhase.CREATED, CaseCarryOver.empty(), 0);
    }

    /** The next generation of this workflow, resuming at {@code phase}. */
    public DisputeCaseInput continuedAt(CasePhase phase, CaseCarryOver newCarryOver) {
        return new DisputeCaseInput(caseId, disputeId, merchantId, transactionId, reasonCode,
                disputeAmount, openedAt, deadlineAt, correlationId, sourceEventId, actor, timers,
                phase, newCarryOver, continuationCount + 1);
    }

    public CaseRef ref() {
        return new CaseRef(caseId, disputeId, merchantId, transactionId, correlationId);
    }

    /** Temporal workflow id for this case. */
    public String workflowId() {
        return workflowIdFor(caseId);
    }

    public static String workflowIdFor(String caseId) {
        return WORKFLOW_ID_PREFIX + caseId;
    }

    /** The caseId embedded in a workflow id, or the argument unchanged when it carries no prefix. */
    public static String caseIdFromWorkflowId(String workflowId) {
        if (workflowId == null) {
            return null;
        }
        return workflowId.startsWith(WORKFLOW_ID_PREFIX)
                ? workflowId.substring(WORKFLOW_ID_PREFIX.length())
                : workflowId;
    }

    public long amountMinor() {
        return disputeAmount == null ? 0L : disputeAmount.amountMinor();
    }

    public String currency() {
        return disputeAmount == null ? null : disputeAmount.currency();
    }
}
