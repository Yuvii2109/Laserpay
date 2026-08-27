package com.laserpay.pdei.orchestrator.workflow;

import com.laserpay.pdei.orchestrator.model.CancelCaseSignal;
import com.laserpay.pdei.orchestrator.model.CaseOutcome;
import com.laserpay.pdei.orchestrator.model.CaseProgress;
import com.laserpay.pdei.orchestrator.model.CaseState;
import com.laserpay.pdei.orchestrator.model.DisputeCaseInput;
import com.laserpay.pdei.orchestrator.model.DisputeUpdatedSignal;
import com.laserpay.pdei.orchestrator.model.EvidenceArrivedSignal;
import com.laserpay.pdei.orchestrator.model.HumanDecision;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The long-running dispute workflow of PLATFORM-CONTRACT section 10.
 *
 * <p>Workflow id is {@code case-{caseId}}, namespace {@code pdei}, task queue
 * {@code pdei-dispute-cases}. One execution per case; the id is derived deterministically from the
 * dispute id so a redelivered {@code DisputeCreated} cannot open a second one.</p>
 *
 * <p>The twelve steps, in order:</p>
 * <ol>
 *   <li>{@code openCase}</li>
 *   <li>{@code gatherEvidence}</li>
 *   <li>{@code detectGaps}</li>
 *   <li>{@code awaitMissingEvidence} - timer plus {@link #evidenceArrived} signal, max 7 days</li>
 *   <li>{@code runAdmissionControl}</li>
 *   <li>{@code investigate} - calls the AI service, or runs deterministically when admission
 *       control short-circuited</li>
 *   <li>{@code validateAndGate}</li>
 *   <li>{@code awaitHumanApproval} - {@link #humanDecision} signal, timeout escalates</li>
 *   <li>{@code prepareRepresentmentPackage}</li>
 *   <li>{@code submitRepresentment}</li>
 *   <li>{@code followUp} - timer loop until a terminal {@link #disputeUpdated} or the deadline</li>
 *   <li>{@code closeCase}</li>
 * </ol>
 *
 * <p>Steps 2 to 8 may repeat when a reviewer answers {@code REQUEST_MORE_EVIDENCE}; steps 1 and
 * 9 to 12 run at most once.</p>
 */
@WorkflowInterface
public interface DisputeCaseWorkflow {

    /** Task queue this workflow and its activities run on (PLATFORM-CONTRACT section 10). */
    String TASK_QUEUE = "pdei-dispute-cases";

    /** Temporal namespace (PLATFORM-CONTRACT section 2 and 10). */
    String NAMESPACE = "pdei";

    /** Workflow type name, used by untyped stubs and by the Temporal UI. */
    String WORKFLOW_TYPE = "DisputeCaseWorkflow";

    @WorkflowMethod
    CaseOutcome run(DisputeCaseInput input);

    // --- signals ------------------------------------------------------------------------------

    /**
     * New evidence landed on the case's transaction. Wakes step 4 so it can re-run
     * {@code gatherEvidence} and {@code detectGaps}; ignored, harmlessly, at any other step.
     */
    @SignalMethod
    void evidenceArrived(EvidenceArrivedSignal signal);

    /** A reviewer approved, rejected, force-submitted, or asked for more evidence at step 8. */
    @SignalMethod
    void humanDecision(HumanDecision decision);

    /**
     * The dispute changed underneath the case - typically {@code DisputeUpdated} or
     * {@code DisputeClosed} on {@code pdei.dispute.events.v1}. A terminal status ends the step 11
     * follow-up loop.
     */
    @SignalMethod
    void disputeUpdated(DisputeUpdatedSignal signal);

    /** Stop the case gracefully: finish the current activity, close as CANCELLED, return. */
    @SignalMethod
    void cancelCase(CancelCaseSignal signal);

    // --- queries ------------------------------------------------------------------------------

    /** Full in-memory case state. Side-effect free. */
    @QueryMethod
    CaseState getCaseState();

    /** Step, percentage and what the case is waiting for. Side-effect free. */
    @QueryMethod
    CaseProgress getProgress();
}
