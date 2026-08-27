package com.laserpay.pdei.orchestrator.signal;

import com.google.protobuf.Timestamp;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.orchestrator.config.TemporalConfig;
import com.laserpay.pdei.orchestrator.model.CancelCaseSignal;
import com.laserpay.pdei.orchestrator.model.CaseProgress;
import com.laserpay.pdei.orchestrator.model.CaseState;
import com.laserpay.pdei.orchestrator.model.DisputeCaseInput;
import com.laserpay.pdei.orchestrator.model.DisputeUpdatedSignal;
import com.laserpay.pdei.orchestrator.model.EvidenceArrivedSignal;
import com.laserpay.pdei.orchestrator.model.HumanDecision;
import com.laserpay.pdei.orchestrator.model.HumanDecisionType;
import com.laserpay.pdei.orchestrator.workflow.DisputeCaseWorkflow;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The only way anything outside Temporal talks to a running case.
 *
 * <p>Two callers use it: {@code listener.DisputeEventListener} (starts and dispute signals) and
 * {@code api.OrchestratorController}, which the api-gateway calls for
 * {@code POST /api/v1/cases/{caseId}/approve|reject|submit}. Keeping the Temporal client behind
 * this service is what lets the gateway stay a plain HTTP service with no Temporal dependency at
 * all.</p>
 *
 * <p><b>Every method tolerates a workflow that is not there.</b> Signals return {@code false}
 * rather than throwing when no execution matches, because a signal for a case that already closed
 * is a normal race - a reviewer clicking approve a second after the deadline timer fired - and not
 * an error worth a 500.</p>
 */
@Service
public class CaseSignalService {

    private static final Logger log = LoggerFactory.getLogger(CaseSignalService.class);

    private final WorkflowClient workflowClient;
    private final TemporalConfig temporalConfig;
    private final Clocks clock;

    public CaseSignalService(WorkflowClient workflowClient, TemporalConfig temporalConfig,
                             Clocks clock) {
        this.workflowClient = workflowClient;
        this.temporalConfig = temporalConfig;
        this.clock = clock;
    }

    // --- start ------------------------------------------------------------------------------

    /**
     * Start the case workflow, or do nothing when one already exists for this workflow id.
     *
     * @return true when this call started a new execution
     */
    public boolean startCase(DisputeCaseInput input) {
        String workflowId = input.workflowId();
        DisputeCaseWorkflow stub = workflowClient.newWorkflowStub(DisputeCaseWorkflow.class,
                temporalConfig.workflowOptions(workflowId));
        try {
            var execution = WorkflowClient.start(stub::run, input);
            log.info("started workflow {} (runId {}) for dispute {}", workflowId,
                    execution.getRunId(), input.disputeId());
            return true;
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            // Exactly what the WorkflowIdReusePolicy is for: a redelivered DisputeCreated, a replayed
            // topic, or two partitions racing. Nothing to do.
            log.info("workflow {} already exists for dispute {}; duplicate start ignored", workflowId,
                    input.disputeId());
            return false;
        }
    }

    // --- signals ----------------------------------------------------------------------------

    public boolean evidenceArrived(String caseId, String evidenceId, EvidenceType evidenceType,
                                   String sourceEventId) {
        return signal(caseId, "evidenceArrived", workflow -> workflow.evidenceArrived(
                new EvidenceArrivedSignal(evidenceId, evidenceType, sourceEventId, clock.now())));
    }

    public boolean approve(String caseId, String actor, String notes) {
        return decide(caseId, HumanDecisionType.APPROVE, actor, notes);
    }

    public boolean reject(String caseId, String actor, String notes) {
        return decide(caseId, HumanDecisionType.REJECT, actor, notes);
    }

    /** {@code POST /api/v1/cases/{caseId}/submit}: approve and proceed straight to submission. */
    public boolean submit(String caseId, String actor, String notes) {
        return decide(caseId, HumanDecisionType.SUBMIT, actor, notes);
    }

    public boolean requestMoreEvidence(String caseId, String actor, String notes) {
        return decide(caseId, HumanDecisionType.REQUEST_MORE_EVIDENCE, actor, notes);
    }

    public boolean decide(String caseId, HumanDecisionType decision, String actor, String notes) {
        HumanDecision payload = new HumanDecision(decision, actor, notes, clock.now());
        return signal(caseId, "humanDecision", workflow -> workflow.humanDecision(payload));
    }

    public boolean disputeUpdated(String caseId, DisputeStatus status, String eventId, String reason,
                                  Instant occurredAt) {
        DisputeUpdatedSignal payload = new DisputeUpdatedSignal(status, eventId, reason,
                occurredAt == null ? clock.now() : occurredAt);
        return signal(caseId, "disputeUpdated", workflow -> workflow.disputeUpdated(payload));
    }

    public boolean cancelCase(String caseId, String reason, String actor) {
        CancelCaseSignal payload = new CancelCaseSignal(reason, actor, clock.now());
        return signal(caseId, "cancelCase", workflow -> workflow.cancelCase(payload));
    }

    // --- queries ----------------------------------------------------------------------------

    public CaseState getCaseState(String caseId) {
        return query(caseId, "getCaseState", DisputeCaseWorkflow::getCaseState);
    }

    public CaseProgress getProgress(String caseId) {
        return query(caseId, "getProgress", DisputeCaseWorkflow::getProgress);
    }

    // --- control ----------------------------------------------------------------------------

    /**
     * Hard stop. Unlike the {@code cancelCase} signal this does not let the workflow close its case
     * row cleanly, so it is the tool of last resort: the case is left in whatever status it had.
     */
    public boolean terminate(String caseId, String reason, String actor) {
        String workflowId = DisputeCaseInput.workflowIdFor(caseId);
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
            stub.terminate(reason == null ? "terminated by operator" : reason, actor);
            log.warn("terminated workflow {} by {}: {}", workflowId, actor, reason);
            return true;
        } catch (WorkflowNotFoundException e) {
            log.info("terminate: no running workflow {}", workflowId);
            return false;
        }
    }

    /** Temporal's own view of the execution: status, run id, history size, timestamps. */
    public CaseWorkflowDescription describe(String caseId) {
        String workflowId = DisputeCaseInput.workflowIdFor(caseId);
        try {
            DescribeWorkflowExecutionResponse response = workflowClient.getWorkflowServiceStubs()
                    .blockingStub()
                    .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(TemporalConfig.NAMESPACE)
                            .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .build())
                            .build());
            var info = response.getWorkflowExecutionInfo();
            String status = info.getStatus().name();
            return new CaseWorkflowDescription(
                    caseId,
                    info.getExecution().getWorkflowId(),
                    info.getExecution().getRunId(),
                    info.getType().getName(),
                    info.getTaskQueue(),
                    status,
                    info.getHistoryLength(),
                    toInstant(info.getStartTime()),
                    toInstant(info.getExecutionTime()),
                    info.hasCloseTime() ? toInstant(info.getCloseTime()) : null,
                    "WORKFLOW_EXECUTION_STATUS_RUNNING".equals(status));
        } catch (RuntimeException e) {
            log.info("describe: no execution for workflow {} ({})", workflowId, e.toString());
            return CaseWorkflowDescription.notFound(caseId, workflowId);
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    private boolean signal(String caseId, String signalName,
                           java.util.function.Consumer<DisputeCaseWorkflow> action) {
        String workflowId = DisputeCaseInput.workflowIdFor(caseId);
        try {
            action.accept(workflowClient.newWorkflowStub(DisputeCaseWorkflow.class, workflowId));
            log.info("signalled {} to workflow {}", signalName, workflowId);
            return true;
        } catch (WorkflowNotFoundException e) {
            log.warn("signal {} dropped: no running workflow {}", signalName, workflowId);
            return false;
        } catch (CanceledFailure e) {
            log.warn("signal {} dropped: workflow {} is cancelled", signalName, workflowId);
            return false;
        }
    }

    private <T> T query(String caseId, String queryName,
                        java.util.function.Function<DisputeCaseWorkflow, T> action) {
        String workflowId = DisputeCaseInput.workflowIdFor(caseId);
        try {
            return action.apply(workflowClient.newWorkflowStub(DisputeCaseWorkflow.class, workflowId));
        } catch (WorkflowNotFoundException e) {
            throw new NotFoundException("CASE_WORKFLOW", workflowId);
        } catch (RuntimeException e) {
            // A query against a failed or terminated execution throws; the caller wants to know why.
            log.warn("query {} on workflow {} failed: {}", queryName, workflowId, e.toString());
            throw e;
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
