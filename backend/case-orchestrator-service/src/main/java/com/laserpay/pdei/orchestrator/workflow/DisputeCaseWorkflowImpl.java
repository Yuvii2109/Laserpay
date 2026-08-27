package com.laserpay.pdei.orchestrator.workflow;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.error.PolicyViolationException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.orchestrator.activity.CaseActivities;
import com.laserpay.pdei.orchestrator.model.AdmissionOutcome;
import com.laserpay.pdei.orchestrator.model.CancelCaseSignal;
import com.laserpay.pdei.orchestrator.model.CaseCarryOver;
import com.laserpay.pdei.orchestrator.model.CaseEventCommand;
import com.laserpay.pdei.orchestrator.model.CaseOutcome;
import com.laserpay.pdei.orchestrator.model.CasePhase;
import com.laserpay.pdei.orchestrator.model.CaseProgress;
import com.laserpay.pdei.orchestrator.model.CaseRef;
import com.laserpay.pdei.orchestrator.model.CaseResolution;
import com.laserpay.pdei.orchestrator.model.CaseState;
import com.laserpay.pdei.orchestrator.model.CaseTimers;
import com.laserpay.pdei.orchestrator.model.CloseCaseRequest;
import com.laserpay.pdei.orchestrator.model.CloseCaseResult;
import com.laserpay.pdei.orchestrator.model.DisputeCaseInput;
import com.laserpay.pdei.orchestrator.model.DisputeUpdatedSignal;
import com.laserpay.pdei.orchestrator.model.EvidenceArrivedSignal;
import com.laserpay.pdei.orchestrator.model.EvidenceReport;
import com.laserpay.pdei.orchestrator.model.GapReport;
import com.laserpay.pdei.orchestrator.model.GateOutcome;
import com.laserpay.pdei.orchestrator.model.GateRequest;
import com.laserpay.pdei.orchestrator.model.HumanDecision;
import com.laserpay.pdei.orchestrator.model.HumanDecisionType;
import com.laserpay.pdei.orchestrator.model.InvestigationOutcome;
import com.laserpay.pdei.orchestrator.model.InvestigationRequest;
import com.laserpay.pdei.orchestrator.model.OpenCaseRequest;
import com.laserpay.pdei.orchestrator.model.OpenCaseResult;
import com.laserpay.pdei.orchestrator.model.PackageResult;
import com.laserpay.pdei.orchestrator.model.PreparePackageRequest;
import com.laserpay.pdei.orchestrator.model.SubmissionReceipt;
import com.laserpay.pdei.orchestrator.model.SubmitRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The twelve-step dispute workflow of PLATFORM-CONTRACT section 10.
 *
 * <p><b>Determinism rules this class obeys, without exception:</b></p>
 * <ul>
 *   <li>no {@code Instant.now()}, no {@code System.currentTimeMillis()} - time comes from
 *       {@link Workflow#currentTimeMillis()}, which replays identically;</li>
 *   <li>no randomness at all (if any were needed it would come from {@code Workflow.newRandom()});</li>
 *   <li>no database, HTTP, MinIO, Kafka or Spring bean access - every side effect goes through
 *       {@link CaseActivities};</li>
 *   <li>no configuration reads - all durations arrive pinned inside {@link CaseTimers} on the
 *       workflow input, so a config change cannot make a replay diverge from the original run;</li>
 *   <li>no logging through a static SLF4J logger - {@link Workflow#getLogger} suppresses duplicate
 *       lines during replay.</li>
 * </ul>
 *
 * <p><b>Shape of the execution.</b> Step 1 runs once. Steps 2 to 8 form an assessment loop that
 * repeats when a reviewer answers {@code REQUEST_MORE_EVIDENCE}, bounded by
 * {@link CaseTimers#maxAssessmentRounds()}. Steps 9 to 12 run once, and 11 is itself a timer loop.
 * The two genuinely long waits - step 4 (up to 7 days) and step 11 (up to 45 days) - can
 * continue-as-new, carrying their elapsed budget forward in {@link CaseCarryOver} so the clock
 * never restarts.</p>
 *
 * <p><b>Failure handling.</b> Compensations are registered as the workflow acquires state, and a
 * failure runs them in reverse before marking the case FAILED and failing the workflow. Failing
 * rather than returning is deliberate: it makes the failure visible in the Temporal UI, and it lets
 * the {@code WorkflowIdReusePolicy} of {@code ALLOW_DUPLICATE_FAILED_ONLY} restart the case from a
 * later redelivery of the same {@code DisputeCreated} event.</p>
 */
@WorkflowImpl(taskQueues = DisputeCaseWorkflow.TASK_QUEUE)
public class DisputeCaseWorkflowImpl implements DisputeCaseWorkflow {

    private static final Logger log = Workflow.getLogger(DisputeCaseWorkflowImpl.class);

    /** PLATFORM-CONTRACT section 10, verbatim: 1s initial, x2.0, 60s cap, 10 attempts. */
    static final RetryOptions ACTIVITY_RETRY_OPTIONS = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0d)
            .setMaximumInterval(Duration.ofSeconds(60))
            .setMaximumAttempts(10)
            // A policy violation or a validation failure is a statement about the request, not about
            // the infrastructure. Retrying it ten times only delays the human who has to look at it.
            .setDoNotRetry(
                    PolicyViolationException.class.getName(),
                    ValidationException.class.getName())
            .build();

    /** Reads, scoring, gate decisions and event publication: fast, bounded work. */
    static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setScheduleToCloseTimeout(Duration.ofHours(1))
            .setRetryOptions(ACTIVITY_RETRY_OPTIONS)
            .build();

    /**
     * Package assembly re-reads and re-hashes every artifact from MinIO, and submission writes a
     * receipt back; both deserve a longer ceiling than a database read.
     */
    static final ActivityOptions PACKAGING_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofMinutes(15))
            .setScheduleToCloseTimeout(Duration.ofHours(4))
            .setRetryOptions(ACTIVITY_RETRY_OPTIONS)
            .build();

    /** Bound on the query payload; a case that logs more than this is already pathological. */
    private static final int MAX_RECORDED_STEPS = 64;

    private final CaseActivities activities =
            Workflow.newActivityStub(CaseActivities.class, ACTIVITY_OPTIONS);
    private final CaseActivities packagingActivities =
            Workflow.newActivityStub(CaseActivities.class, PACKAGING_ACTIVITY_OPTIONS);

    // --- workflow state (all of it replay-derived) ------------------------------------------

    private DisputeCaseInput input;
    private CaseTimers timers = CaseTimers.defaults();

    private CasePhase phase = CasePhase.CREATED;
    private CaseStatus caseStatus = CaseStatus.CREATED;
    private DisputeStatus disputeStatus = DisputeStatus.OPEN;
    private CaseResolution resolution;

    private EvidenceReport evidenceReport;
    private GapReport gapReport;
    private AdmissionOutcome admission;
    private InvestigationOutcome investigation;
    private GateOutcome gate;
    private PackageResult packageResult;
    private SubmissionReceipt receipt;

    private HumanDecision pendingDecision;
    private HumanDecision appliedDecision;

    private final Set<String> arrivedEvidenceIds = new LinkedHashSet<>();
    private final Set<String> seenDisputeEventIds = new LinkedHashSet<>();
    private final List<String> completedSteps = new ArrayList<>();

    private int evidenceSignalCount;
    private int assessmentRound;
    private int followUpTick;
    private long evidenceWaitElapsedMillis;
    private long followUpElapsedMillis;

    private boolean disputeTerminal;
    private boolean cancelled;
    private String cancelReason;
    private String failureReason;

    // --- workflow method ----------------------------------------------------------------------

    @Override
    public CaseOutcome run(DisputeCaseInput in) {
        this.input = in;
        this.timers = CaseTimers.orDefaults(in == null ? null : in.timers());
        CaseCarryOver carried = CaseCarryOver.orEmpty(in == null ? null : in.carryOver());
        this.assessmentRound = carried.assessmentRound();
        this.followUpTick = carried.followUpTick();
        this.evidenceWaitElapsedMillis = carried.evidenceWaitElapsedMillis();
        this.followUpElapsedMillis = carried.followUpElapsedMillis();
        this.packageResult = carried.packageResult();
        this.receipt = carried.submissionReceipt();

        CasePhase resume = in == null || in.resumePhase() == null ? CasePhase.CREATED : in.resumePhase();

        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false)
                .setContinueWithError(true)
                .build());

        try {
            if (resume == CasePhase.FOLLOW_UP) {
                // Continued from step 11: steps 1-10 already happened in an earlier run.
                caseStatus = CaseStatus.SUBMITTED;
                disputeStatus = DisputeStatus.SUBMITTED;
                registerSubmissionCompensation(saga);
                return followUpThenClose();
            }

            step1OpenCase(saga);

            AssessmentDecision decision = runAssessmentLoop(resume);
            if (decision.stopped()) {
                return closeWith(decision.resolution(), decision.reason(), decision.disputeStatus());
            }

            step9PrepareRepresentmentPackage(saga);
            step10SubmitRepresentment(saga);
            return followUpThenClose();

        } catch (CanceledFailure cancellation) {
            // Temporal-level cancellation (not the cancelCase signal). The closing activity has to
            // run in a detached scope, otherwise it would be cancelled along with everything else
            // and the case row would be left mid-flight.
            log.warn("case {} cancelled by Temporal: {}", caseId(), cancellation.getMessage());
            cancelled = true;
            cancelReason = "workflow execution cancelled";
            CaseOutcome[] closed = new CaseOutcome[1];
            Workflow.newDetachedCancellationScope(
                    () -> closed[0] = closeWith(CaseResolution.CANCELLED, cancelReason, null)).run();
            return closed[0];
        } catch (Exception failure) {
            // ContinueAsNewError is an Error, not an Exception, so continue-as-new is never caught here.
            return failAndCompensate(saga, failure);
        }
    }

    // --- steps ---------------------------------------------------------------------------------

    /** Step 1. */
    private void step1OpenCase(Saga saga) {
        phase = CasePhase.OPENING;
        OpenCaseResult result = activities.openCase(new OpenCaseRequest(
                input.caseId(), input.disputeId(), input.merchantId(), input.transactionId(),
                input.disputeAmount(), input.openedAt(), input.deadlineAt(),
                Workflow.getInfo().getWorkflowId(), Workflow.getInfo().getRunId(),
                input.correlationId(), actor()));

        caseStatus = result.caseStatus() == null ? CaseStatus.CREATED : result.caseStatus();
        if (result.disputeStatus() != null) {
            disputeStatus = result.disputeStatus();
            disputeTerminal = isTerminal(result.disputeStatus());
        }
        // If the whole run dies later, the case must not be left looking half-opened.
        saga.addCompensation(() -> activities.closeCase(new CloseCaseRequest(
                ref(), CaseResolution.FAILED, CaseStatus.FAILED, null,
                "workflow failed after openCase", actor(), true, token("compensate-open"))));

        completed("openCase" + (result.alreadyOpen() ? " (adopted existing case row)" : ""));
    }

    /**
     * Steps 2 to 8. Returns a stop decision when the case must end here, or "proceed" when the
     * representment is cleared for assembly.
     */
    private AssessmentDecision runAssessmentLoop(CasePhase resume) {
        boolean resumingEvidenceWait = resume == CasePhase.AWAITING_EVIDENCE;

        while (true) {
            if (!resumingEvidenceWait) {
                assessmentRound++;
            }
            resumingEvidenceWait = false;

            step2GatherEvidence();
            step3DetectGaps();
            step4AwaitMissingEvidence();

            AssessmentDecision interrupt = checkInterrupts();
            if (interrupt.stopped()) {
                return interrupt;
            }

            step5RunAdmissionControl();
            step6Investigate();
            step7ValidateAndGate();

            interrupt = checkInterrupts();
            if (interrupt.stopped()) {
                return interrupt;
            }

            if (gate.autoApproved()) {
                completed("validateAndGate: ALLOW, proceeding without human review");
                return AssessmentDecision.proceed();
            }

            HumanDecisionType outcome = step8AwaitHumanApproval();

            interrupt = checkInterrupts();
            if (interrupt.stopped()) {
                return interrupt;
            }
            if (outcome == null) {
                return AssessmentDecision.stop(CaseResolution.ESCALATION_EXPIRED,
                        "no human decision within the approval and escalation windows",
                        DisputeStatus.AWAITING_HUMAN_REVIEW);
            }
            switch (outcome) {
                case APPROVE, SUBMIT -> {
                    return AssessmentDecision.proceed();
                }
                case REJECT -> {
                    boolean acceptedLiability = investigation != null
                            && investigation.recommendedAction() == RecommendedAction.ACCEPT_LIABILITY;
                    return AssessmentDecision.stop(
                            acceptedLiability ? CaseResolution.LIABILITY_ACCEPTED
                                    : CaseResolution.REJECTED_BY_HUMAN,
                            reviewerNotes("reviewer declined to submit"),
                            DisputeStatus.LOST);
                }
                case REQUEST_MORE_EVIDENCE -> {
                    if (assessmentRound >= timers.maxAssessmentRounds()) {
                        return AssessmentDecision.stop(CaseResolution.EVIDENCE_INSUFFICIENT,
                                "evidence still insufficient after " + assessmentRound
                                        + " assessment round(s)",
                                DisputeStatus.AWAITING_HUMAN_REVIEW);
                    }
                    completed("humanDecision: REQUEST_MORE_EVIDENCE, restarting assessment");
                }
                default -> throw new IllegalStateException("unhandled decision " + outcome);
            }
        }
    }

    /** Step 2. */
    private void step2GatherEvidence() {
        phase = CasePhase.GATHERING_EVIDENCE;
        caseStatus = CaseStatus.ASSEMBLING;
        evidenceReport = activities.gatherEvidence(ref());
        activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CaseEvidenceAttached,
                caseStatus, phase,
                CaseEventCommand.payloadOf(
                        "evidenceCount", evidenceReport.evidenceCount(),
                        "usableEvidenceCount", evidenceReport.usableEvidenceCount(),
                        "readinessScore", evidenceReport.readinessScore(),
                        "readinessBand", nameOf(evidenceReport.readinessBand()),
                        "assessmentRound", assessmentRound),
                key("evidence-attached", assessmentRound)));
        completed("gatherEvidence: " + evidenceReport.usableEvidenceCount() + " usable artifact(s), readiness "
                + evidenceReport.readinessScore());
    }

    /** Step 3. */
    private void step3DetectGaps() {
        phase = CasePhase.DETECTING_GAPS;
        gapReport = activities.detectGaps(ref());
        completed("detectGaps: " + gapReport.gapCount() + " gap(s), " + gapReport.blockingGapCount()
                + " blocking, " + gapReport.contradictionCount() + " contradiction(s)");
    }

    /**
     * Step 4. The 7-day missing-evidence wait.
     *
     * <p>Skipped entirely when nothing blocking is missing - waiting a week for an OPTIONAL document
     * nobody needs would be a bug, not caution. Otherwise the wait is spent in slices so that each
     * {@code evidenceArrived} signal can re-run steps 2 and 3, and so the workflow can
     * continue-as-new without losing the elapsed budget.</p>
     */
    private void step4AwaitMissingEvidence() {
        if (gapReport == null || !gapReport.hasBlockingGaps()) {
            completed("awaitMissingEvidence: skipped, no blocking gaps");
            return;
        }

        phase = CasePhase.AWAITING_EVIDENCE;
        caseStatus = CaseStatus.AWAITING_EVIDENCE;
        activities.publishCaseEvent(CaseEventCommand.statusOnly(ref(), caseStatus, phase,
                key("await-evidence", assessmentRound)));

        long budgetMillis = timers.missingEvidenceWait().toMillis();
        long sliceMillis = Math.max(1_000L, timers.evidenceWaitSlice().toMillis());
        long startedAt = Workflow.currentTimeMillis();

        while (true) {
            long spent = evidenceWaitElapsedMillis + (Workflow.currentTimeMillis() - startedAt);
            long remaining = budgetMillis - spent;
            if (remaining <= 0L) {
                completed("awaitMissingEvidence: " + timers.missingEvidenceWait().toDays()
                        + "-day budget exhausted with " + gapReport.blockingGapCount() + " blocking gap(s)");
                evidenceWaitElapsedMillis = budgetMillis;
                return;
            }
            if (cancelled || disputeTerminal || pastDeadline()) {
                evidenceWaitElapsedMillis = spent;
                return;
            }

            int seen = evidenceSignalCount;
            Duration slice = Duration.ofMillis(Math.min(remaining, sliceMillis));
            Workflow.await(slice, () -> evidenceSignalCount > seen || cancelled || disputeTerminal);

            if (cancelled || disputeTerminal) {
                evidenceWaitElapsedMillis =
                        evidenceWaitElapsedMillis + (Workflow.currentTimeMillis() - startedAt);
                return;
            }

            if (evidenceSignalCount > seen) {
                evidenceReport = activities.gatherEvidence(ref());
                gapReport = activities.detectGaps(ref());
                activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CaseEvidenceAttached,
                        caseStatus, phase,
                        CaseEventCommand.payloadOf(
                                "trigger", "evidenceArrived",
                                "evidenceCount", evidenceReport.evidenceCount(),
                                "readinessScore", evidenceReport.readinessScore(),
                                "blockingGapCount", gapReport.blockingGapCount()),
                        key("evidence-arrived", evidenceSignalCount)));
                if (!gapReport.hasBlockingGaps()) {
                    evidenceWaitElapsedMillis =
                            evidenceWaitElapsedMillis + (Workflow.currentTimeMillis() - startedAt);
                    completed("awaitMissingEvidence: blocking gaps closed after "
                            + arrivedEvidenceIds.size() + " arrival signal(s)");
                    return;
                }
            }

            if (shouldContinueAsNew()) {
                long elapsed = evidenceWaitElapsedMillis + (Workflow.currentTimeMillis() - startedAt);
                continueAsNewFrom(CasePhase.AWAITING_EVIDENCE, elapsed, followUpElapsedMillis);
            }
        }
    }

    /** Step 5. */
    private void step5RunAdmissionControl() {
        phase = CasePhase.ADMISSION_CONTROL;
        caseStatus = CaseStatus.INVESTIGATING;
        admission = activities.runAdmissionControl(ref(), token("admission"));
        completed("runAdmissionControl: " + (admission.admit() ? "ADMITTED" : admission.shortCircuit())
                + " priority " + admission.priority());
    }

    /**
     * Step 6. Contract section 10 calls this step skippable; what is skipped is the model call, not
     * the investigation. When admission control declines, the activity runs
     * {@code DeterministicInvestigator} instead, so the gate always has something concrete to judge.
     */
    private void step6Investigate() {
        phase = CasePhase.INVESTIGATING;
        investigation = activities.investigate(new InvestigationRequest(
                ref(), admission != null && admission.admit(),
                admission == null ? null : admission.reason(),
                token("investigate")));

        activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CaseInvestigated,
                caseStatus, phase,
                CaseEventCommand.payloadOf(
                        "investigationId", investigation.investigationId(),
                        "classification", nameOf(investigation.classification()),
                        "confidence", investigation.confidence(),
                        "recommendedAction", nameOf(investigation.recommendedAction()),
                        "aiUsed", investigation.aiUsed(),
                        "provider", investigation.provider()),
                key("investigated", assessmentRound)));

        completed("investigate: " + investigation.classification() + " confidence "
                + investigation.confidence() + (investigation.aiUsed() ? " (AI)" : " (deterministic)"));
    }

    /** Step 7. */
    private void step7ValidateAndGate() {
        phase = CasePhase.GATING;
        gate = activities.validateAndGate(new GateRequest(ref(), investigation.investigationId(),
                investigation.aiUsed(), token("gate")));
        completed("validateAndGate: " + gate.decision()
                + (gate.reasons().isEmpty() ? "" : " - " + gate.reasons()));
    }

    /**
     * Step 8. Waits for {@code humanDecision}. On timeout the case escalates - a
     * {@code CaseEscalated} event plus a second, longer window - and only then gives up.
     *
     * @return the reviewer's decision, or {@code null} when nobody answered or the wait was
     *         interrupted by cancellation or a terminal dispute
     */
    private HumanDecisionType step8AwaitHumanApproval() {
        phase = CasePhase.AWAITING_APPROVAL;
        caseStatus = CaseStatus.AWAITING_APPROVAL;
        activities.publishCaseEvent(CaseEventCommand.statusOnly(ref(), caseStatus, phase,
                key("await-approval", assessmentRound)));

        boolean decided = Workflow.await(timers.humanApprovalTimeout(),
                () -> pendingDecision != null || cancelled || disputeTerminal);

        if (cancelled || disputeTerminal) {
            return null;
        }

        if (!decided) {
            phase = CasePhase.ESCALATED;
            activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CaseEscalated,
                    caseStatus, phase,
                    CaseEventCommand.payloadOf(
                            "reason", "no human decision within " + timers.humanApprovalTimeout(),
                            "gateDecision", gate == null ? null : nameOf(gate.decision()),
                            "escalationWindow", timers.escalationTimeout().toString(),
                            "assessmentRound", assessmentRound),
                    key("escalated", assessmentRound)));
            completed("awaitHumanApproval: escalated after " + timers.humanApprovalTimeout());

            Workflow.await(timers.escalationTimeout(),
                    () -> pendingDecision != null || cancelled || disputeTerminal);

            if (cancelled || disputeTerminal) {
                return null;
            }
        }

        if (pendingDecision == null) {
            completed("awaitHumanApproval: escalation window expired with no decision");
            return null;
        }

        appliedDecision = pendingDecision;
        pendingDecision = null;
        completed("awaitHumanApproval: " + appliedDecision.decision() + " by "
                + (appliedDecision.actor() == null ? "unknown" : appliedDecision.actor()));
        return appliedDecision.decision();
    }

    /** Step 9. */
    private void step9PrepareRepresentmentPackage(Saga saga) {
        phase = CasePhase.PREPARING_PACKAGE;
        caseStatus = CaseStatus.ASSEMBLING;
        packageResult = packagingActivities.prepareRepresentmentPackage(
                new PreparePackageRequest(ref(), approver(), token("package")));

        caseStatus = CaseStatus.PREPARED;
        disputeStatus = DisputeStatus.REPRESENTMENT_PREPARED;

        // A prepared but unsubmitted package is not something a machine can undo: the bytes are in
        // MinIO and the manifest is signed by their hashes. Compensation therefore escalates it to a
        // human rather than pretending to roll it back.
        PackageResult assembled = packageResult;
        saga.addCompensation(() -> activities.publishCaseEvent(CaseEventCommand.of(ref(),
                EventType.CaseEscalated, CaseStatus.FAILED, CasePhase.FAILED,
                CaseEventCommand.payloadOf(
                        "compensation", "PACKAGE_PREPARED_BUT_WORKFLOW_FAILED",
                        "bundleObjectKey", assembled.bundleObjectKey(),
                        "bundleSha256", assembled.bundleSha256(),
                        "packageVersion", assembled.packageVersion(),
                        "action", "review the assembled bundle before any manual submission"),
                key("compensate-package", assembled.packageVersion()))));

        activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CasePrepared,
                caseStatus, phase,
                CaseEventCommand.payloadOf(
                        "manifestId", packageResult.manifestId(),
                        "packageVersion", packageResult.packageVersion(),
                        "bundleObjectKey", packageResult.bundleObjectKey(),
                        "bundleSha256", packageResult.bundleSha256(),
                        "itemCount", packageResult.itemCount(),
                        "readinessScore", packageResult.readinessScore()),
                key("prepared", packageResult.packageVersion())));

        completed("prepareRepresentmentPackage: v" + packageResult.packageVersion() + ", "
                + packageResult.itemCount() + " artifact(s), sha256 " + packageResult.bundleSha256());
    }

    /** Step 10. */
    private void step10SubmitRepresentment(Saga saga) {
        phase = CasePhase.SUBMITTING;
        receipt = packagingActivities.submitRepresentment(
                new SubmitRequest(ref(), packageResult, approver(), token("submit")));

        caseStatus = CaseStatus.SUBMITTED;
        disputeStatus = DisputeStatus.SUBMITTED;
        registerSubmissionCompensation(saga);

        activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CaseSubmitted,
                caseStatus, phase,
                CaseEventCommand.payloadOf(
                        "submissionId", receipt.submissionId(),
                        "networkReference", receipt.networkReference(),
                        "submitter", receipt.submitterName(),
                        "simulated", receipt.simulated(),
                        "packageVersion", receipt.packageVersion(),
                        "bundleSha256", receipt.bundleSha256()),
                key("submitted", receipt.packageVersion())));

        completed("submitRepresentment: " + receipt.networkReference()
                + (receipt.simulated() ? " (SIMULATED)" : ""));
    }

    /** Steps 11 and 12. */
    private CaseOutcome followUpThenClose() {
        step11FollowUp();

        CaseResolution outcome;
        String reason;
        DisputeStatus target = null;
        if (cancelled) {
            outcome = CaseResolution.CANCELLED;
            reason = cancelReason == null ? "cancelled during follow-up" : cancelReason;
        } else if (disputeTerminal) {
            outcome = CaseResolution.SUBMITTED_AND_RESOLVED;
            reason = "dispute closed upstream as " + disputeStatus;
        } else {
            outcome = CaseResolution.SUBMITTED_AWAITING_OUTCOME;
            reason = "follow-up window elapsed with no network outcome";
        }
        return closeWith(outcome, reason, target);
    }

    /**
     * Step 11. Ticks until the dispute reaches a terminal status, the case is cancelled, or the
     * follow-up ceiling is reached.
     *
     * <p>The representment deadline deliberately does <em>not</em> end this loop: the deadline
     * governs when a package may still be submitted, and by this point one already has been.</p>
     */
    private void step11FollowUp() {
        phase = CasePhase.FOLLOW_UP;
        caseStatus = CaseStatus.SUBMITTED;
        activities.publishCaseEvent(CaseEventCommand.statusOnly(ref(), caseStatus, phase,
                key("follow-up-start", followUpTick)));

        long maxMillis = timers.followUpMaxDuration().toMillis();
        long intervalMillis = Math.max(1_000L, timers.followUpInterval().toMillis());
        long startedAt = Workflow.currentTimeMillis();

        while (!disputeTerminal && !cancelled) {
            long spent = followUpElapsedMillis + (Workflow.currentTimeMillis() - startedAt);
            long remaining = maxMillis - spent;
            if (remaining <= 0L) {
                followUpElapsedMillis = maxMillis;
                break;
            }

            Duration slice = Duration.ofMillis(Math.min(remaining, intervalMillis));
            boolean settled = Workflow.await(slice, () -> disputeTerminal || cancelled);
            if (settled) {
                break;
            }

            followUpTick++;
            activities.publishCaseEvent(CaseEventCommand.of(ref(), EventType.CaseSubmitted,
                    caseStatus, phase,
                    CaseEventCommand.payloadOf(
                            "phase", "FOLLOW_UP",
                            "tick", followUpTick,
                            "networkReference", receipt == null ? null : receipt.networkReference(),
                            "awaitingSince", receipt == null ? null : String.valueOf(receipt.submittedAt())),
                    key("follow-up", followUpTick)));

            if (shouldContinueAsNew()) {
                long elapsed = followUpElapsedMillis + (Workflow.currentTimeMillis() - startedAt);
                continueAsNewFrom(CasePhase.FOLLOW_UP, evidenceWaitElapsedMillis, elapsed);
            }
        }

        followUpElapsedMillis = Math.min(maxMillis,
                followUpElapsedMillis + (Workflow.currentTimeMillis() - startedAt));
        completed("followUp: " + followUpTick + " tick(s), dispute " + disputeStatus);
    }

    /** Step 12. */
    private CaseOutcome closeWith(CaseResolution outcome, String reason, DisputeStatus targetDisputeStatus) {
        phase = CasePhase.CLOSING;
        CaseStatus targetCaseStatus =
                outcome == CaseResolution.FAILED ? CaseStatus.FAILED : CaseStatus.CLOSED;

        // Never ask DisputeService to move a dispute that already reached an outcome.
        DisputeStatus requested = disputeTerminal ? null : targetDisputeStatus;

        CloseCaseResult result = activities.closeCase(new CloseCaseRequest(ref(), outcome,
                targetCaseStatus, requested, reason, actor(), false, token("close")));

        caseStatus = result.caseStatus() == null ? targetCaseStatus : result.caseStatus();
        if (result.disputeStatus() != null) {
            disputeStatus = result.disputeStatus();
            disputeTerminal = disputeTerminal || isTerminal(result.disputeStatus());
        }
        resolution = outcome;
        phase = switch (outcome) {
            case FAILED -> CasePhase.FAILED;
            case CANCELLED -> CasePhase.CANCELLED;
            default -> CasePhase.CLOSED;
        };
        completed("closeCase: " + outcome + " - " + reason);

        return new CaseOutcome(caseId(), input.disputeId(), outcome, caseStatus, disputeStatus,
                packageResult == null ? 0 : packageResult.packageVersion(),
                packageResult == null ? null : packageResult.bundleObjectKey(),
                receipt == null ? null : receipt.networkReference(),
                reason, List.copyOf(completedSteps), assessmentRound, continuationCount());
    }

    // --- failure handling -----------------------------------------------------------------------

    /** Runs registered compensations in reverse, marks the case FAILED, then fails the workflow. */
    private CaseOutcome failAndCompensate(Saga saga, Exception failure) {
        failureReason = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        log.error("case {} failed at phase {}: {}", caseId(), phase, failureReason);

        try {
            saga.compensate();
        } catch (RuntimeException compensationFailure) {
            // Saga is configured with continueWithError, so this only fires if compensation itself
            // threw after exhausting its retries. Record it; the case is marked FAILED either way.
            log.error("compensation for case {} did not complete cleanly: {}", caseId(),
                    compensationFailure.toString());
        }

        phase = CasePhase.FAILED;
        caseStatus = CaseStatus.FAILED;
        resolution = CaseResolution.FAILED;
        throw Workflow.wrap(failure);
    }

    private void registerSubmissionCompensation(Saga saga) {
        // A submission cannot be un-sent. The only honest compensation is to make a human aware.
        saga.addCompensation(() -> activities.publishCaseEvent(CaseEventCommand.of(ref(),
                EventType.CaseEscalated, CaseStatus.FAILED, CasePhase.FAILED,
                CaseEventCommand.payloadOf(
                        "compensation", "SUBMITTED_BUT_WORKFLOW_FAILED",
                        "networkReference", receipt == null ? null : receipt.networkReference(),
                        "action", "reconcile the submission with the network manually"),
                key("compensate-submit", followUpTick))));
    }

    // --- signals --------------------------------------------------------------------------------

    @Override
    public void evidenceArrived(EvidenceArrivedSignal signal) {
        String evidenceId = signal == null ? null : signal.evidenceId();
        // A repeated evidenceId is a redelivery, not new information: do not wake the wait for it.
        boolean isNew = evidenceId == null || arrivedEvidenceIds.add(evidenceId);
        if (!isNew) {
            log.debug("case {} ignoring duplicate evidenceArrived for {}", caseId(), evidenceId);
            return;
        }
        evidenceSignalCount++;
        log.info("case {} evidenceArrived: {} ({})", caseId(), evidenceId,
                signal == null ? null : signal.evidenceType());
    }

    @Override
    public void humanDecision(HumanDecision decision) {
        if (decision == null || decision.decision() == null) {
            log.warn("case {} ignoring humanDecision with no decision", caseId());
            return;
        }
        if (pendingDecision != null) {
            // First decision wins. A second signal for the same review is a duplicate delivery or a
            // double click, and silently overwriting a REJECT with an APPROVE would be dangerous.
            log.warn("case {} already holds an unconsumed {} decision; ignoring {}", caseId(),
                    pendingDecision.decision(), decision.decision());
            return;
        }
        pendingDecision = decision;
        log.info("case {} humanDecision {} by {}", caseId(), decision.decision(), decision.actor());
    }

    @Override
    public void disputeUpdated(DisputeUpdatedSignal signal) {
        if (signal == null || signal.status() == null) {
            return;
        }
        if (signal.eventId() != null && !seenDisputeEventIds.add(signal.eventId())) {
            log.debug("case {} ignoring duplicate disputeUpdated for event {}", caseId(), signal.eventId());
            return;
        }
        if (disputeTerminal) {
            // Late or out-of-order: a dispute that reached an outcome never goes back to OPEN.
            log.warn("case {} ignoring {} after terminal dispute status {}", caseId(), signal.status(),
                    disputeStatus);
            return;
        }
        disputeStatus = signal.status();
        disputeTerminal = signal.isTerminal();
        log.info("case {} disputeUpdated -> {}{}", caseId(), disputeStatus,
                disputeTerminal ? " (terminal)" : "");
    }

    @Override
    public void cancelCase(CancelCaseSignal signal) {
        if (cancelled) {
            return;
        }
        cancelled = true;
        cancelReason = signal == null || signal.reason() == null ? "cancelled by operator" : signal.reason();
        log.warn("case {} cancelCase: {}", caseId(), cancelReason);
    }

    // --- queries --------------------------------------------------------------------------------

    @Override
    public CaseState getCaseState() {
        return new CaseState(
                caseId(),
                input == null ? null : input.disputeId(),
                input == null ? null : input.merchantId(),
                input == null ? null : input.transactionId(),
                caseStatus,
                disputeStatus,
                phase,
                input == null ? null : input.disputeAmount(),
                input == null ? null : input.deadlineAt(),

                evidenceReport == null ? 0 : evidenceReport.readinessScore(),
                evidenceReport == null ? null : evidenceReport.readinessBand(),
                evidenceReport == null ? 0 : evidenceReport.evidenceCount(),
                gapReport == null ? 0 : gapReport.gapCount(),
                gapReport == null ? 0 : gapReport.blockingGapCount(),
                gapReport == null ? 0 : gapReport.contradictionCount(),
                evidenceReport != null && evidenceReport.allMandatorySatisfied(),
                List.copyOf(arrivedEvidenceIds),

                admission != null && admission.admit(),
                admission == null ? 0 : admission.priority(),
                admission == null ? null : admission.shortCircuit(),
                investigation == null ? null : investigation.classification(),
                investigation == null ? 0.0d : investigation.confidence(),
                investigation == null ? null : investigation.recommendedAction(),
                gate == null ? null : gate.decision(),
                gate == null ? List.<String>of() : gate.reasons(),

                appliedDecision == null ? null : appliedDecision.decision(),
                appliedDecision == null ? null : appliedDecision.actor(),
                appliedDecision == null ? null : appliedDecision.notes(),

                packageResult == null ? 0 : packageResult.packageVersion(),
                packageResult == null ? null : packageResult.bundleObjectKey(),
                packageResult == null ? null : packageResult.bundleSha256(),
                receipt == null ? null : receipt.networkReference(),
                receipt != null && receipt.simulated(),

                assessmentRound,
                followUpTick,
                continuationCount(),
                cancelled,
                cancelReason,
                failureReason,
                resolution);
    }

    @Override
    public CaseProgress getProgress() {
        return new CaseProgress(
                caseId(),
                phase,
                phase.step(),
                CasePhase.TOTAL_STEPS,
                phase.percent(),
                phase.description(),
                List.copyOf(completedSteps),
                phase.isWaiting(),
                waitingFor(),
                input == null ? null : input.deadlineAt(),
                phase.isTerminal());
    }

    private String waitingFor() {
        return switch (phase) {
            case AWAITING_EVIDENCE -> "evidenceArrived signal, or the missing-evidence timer ("
                    + timers.missingEvidenceWait() + " total)";
            case AWAITING_APPROVAL -> "humanDecision signal, or the approval timeout ("
                    + timers.humanApprovalTimeout() + ")";
            case ESCALATED -> "humanDecision signal after escalation, or the escalation timeout ("
                    + timers.escalationTimeout() + ")";
            case FOLLOW_UP -> "disputeUpdated signal with a terminal status, tick every "
                    + timers.followUpInterval();
            default -> null;
        };
    }

    // --- helpers --------------------------------------------------------------------------------

    /** True when the workflow should hand its remaining work to a fresh event history. */
    private boolean shouldContinueAsNew() {
        var info = Workflow.getInfo();
        return info.isContinueAsNewSuggested()
                || info.getHistoryLength() >= timers.continueAsNewHistoryThreshold();
    }

    /** Never returns: {@code Workflow.continueAsNew} unwinds the workflow thread. */
    private void continueAsNewFrom(CasePhase resumePhase, long evidenceElapsed, long followUpElapsed) {
        CaseCarryOver carryOver = new CaseCarryOver(evidenceElapsed, followUpElapsed, followUpTick,
                assessmentRound, packageResult, receipt);
        log.info("case {} continuing as new at {} (history {} events, generation {})", caseId(),
                resumePhase, Workflow.getInfo().getHistoryLength(), continuationCount());
        Workflow.continueAsNew(input.continuedAt(resumePhase, carryOver));
    }

    private AssessmentDecision checkInterrupts() {
        if (cancelled) {
            return AssessmentDecision.stop(CaseResolution.CANCELLED,
                    cancelReason == null ? "cancelled by operator" : cancelReason, null);
        }
        if (disputeTerminal) {
            CaseResolution outcome = receipt != null
                    ? CaseResolution.SUBMITTED_AND_RESOLVED : CaseResolution.CANCELLED;
            return AssessmentDecision.stop(outcome, "dispute closed upstream as " + disputeStatus, null);
        }
        if (pastDeadline()) {
            return AssessmentDecision.stop(CaseResolution.DEADLINE_EXPIRED,
                    "representment deadline " + input.deadlineAt() + " passed before submission",
                    DisputeStatus.EXPIRED);
        }
        return AssessmentDecision.proceed();
    }

    private boolean pastDeadline() {
        return input != null && input.deadlineAt() != null
                && Workflow.currentTimeMillis() > input.deadlineAt().toEpochMilli();
    }

    private static boolean isTerminal(DisputeStatus status) {
        return status == DisputeStatus.WON || status == DisputeStatus.LOST
                || status == DisputeStatus.EXPIRED || status == DisputeStatus.WITHDRAWN;
    }

    private CaseRef ref() {
        return input.ref();
    }

    private String caseId() {
        return input == null ? null : input.caseId();
    }

    private int continuationCount() {
        return input == null ? 0 : input.continuationCount();
    }

    private String actor() {
        return input == null || input.actor() == null ? "SYSTEM" : input.actor();
    }

    private String approver() {
        return appliedDecision != null && appliedDecision.actor() != null
                ? appliedDecision.actor() : actor();
    }

    private String reviewerNotes(String fallback) {
        if (appliedDecision != null && appliedDecision.notes() != null
                && !appliedDecision.notes().isBlank()) {
            return fallback + ": " + appliedDecision.notes();
        }
        return fallback;
    }

    /** Deterministic idempotency key for a published event. Same key on replay, same event id. */
    private String key(String label, int discriminator) {
        return input.caseId() + ":" + label + ":" + discriminator;
    }

    /** Deterministic memoisation token for an activity that is not naturally idempotent. */
    private String token(String label) {
        return input.caseId() + ":r" + assessmentRound + ":" + label;
    }

    private void completed(String step) {
        if (completedSteps.size() < MAX_RECORDED_STEPS) {
            completedSteps.add(step);
        }
        log.info("case {} step complete: {}", caseId(), step);
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** Outcome of the assessment loop: either "keep going" or "stop the case, here is why". */
    private record AssessmentDecision(CaseResolution resolution, String reason,
                                      DisputeStatus disputeStatus) {

        static AssessmentDecision proceed() {
            return new AssessmentDecision(null, null, null);
        }

        static AssessmentDecision stop(CaseResolution resolution, String reason,
                                       DisputeStatus disputeStatus) {
            return new AssessmentDecision(resolution, reason, disputeStatus);
        }

        boolean stopped() {
            return resolution != null;
        }
    }
}
