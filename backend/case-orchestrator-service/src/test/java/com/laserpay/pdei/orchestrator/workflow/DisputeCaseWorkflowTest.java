package com.laserpay.pdei.orchestrator.workflow;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.orchestrator.model.CaseOutcome;
import com.laserpay.pdei.orchestrator.model.CasePhase;
import com.laserpay.pdei.orchestrator.model.CaseProgress;
import com.laserpay.pdei.orchestrator.model.CaseResolution;
import com.laserpay.pdei.orchestrator.model.CaseState;
import com.laserpay.pdei.orchestrator.model.CaseTimers;
import com.laserpay.pdei.orchestrator.model.DisputeCaseInput;
import com.laserpay.pdei.orchestrator.model.DisputeUpdatedSignal;
import com.laserpay.pdei.orchestrator.model.EvidenceArrivedSignal;
import com.laserpay.pdei.orchestrator.model.HumanDecision;
import com.laserpay.pdei.orchestrator.support.FakeCaseActivities;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four scenarios that matter for a twelve-step dispute workflow, run against Temporal's
 * {@code TestWorkflowEnvironment}.
 *
 * <p>Time is <b>skipped</b>, not slept: the seven-day evidence wait and the forty-eight-hour
 * approval window pass in milliseconds while the workflow experiences them at full length. That is
 * what makes it possible to assert on real contract durations rather than on test-only ones.</p>
 *
 * <p>The data converter is the production one from {@code TemporalConfig}, so these tests also
 * exercise the round-tripping of every record on the workflow boundary - {@code Money},
 * {@code Instant}, {@code Duration}, enums - through the shared {@link Json#mapper()}.</p>
 */
class DisputeCaseWorkflowTest {

    private static final String WORKFLOW_ID =
            DisputeCaseInput.workflowIdFor(FakeCaseActivities.CASE_ID);

    /**
     * Contract-shaped timers with one deliberate change: {@code continueAsNewHistoryThreshold} is
     * raised out of reach so a test asserts on a single execution rather than on a chain of them.
     */
    private static final CaseTimers TIMERS = new CaseTimers(
            Duration.ofDays(7),      // missing evidence wait - the contract value
            Duration.ofHours(12),    // wait slice
            Duration.ofHours(48),    // human approval timeout
            Duration.ofHours(72),    // escalation timeout
            Duration.ofHours(24),    // follow-up interval
            Duration.ofHours(72),    // follow-up ceiling: three ticks, then close
            1_000_000,               // effectively no continue-as-new
            3);

    private TestWorkflowEnvironment testEnv;
    private FakeCaseActivities activities;
    private DisputeCaseWorkflow workflow;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(DefaultDataConverter.newDefaultInstance()
                                .withPayloadConverterOverrides(
                                        new JacksonJsonPayloadConverter(Json.mapper())))
                        .build())
                .build());

        activities = new FakeCaseActivities();

        Worker worker = testEnv.newWorker(DisputeCaseWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DisputeCaseWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        workflow = client.newWorkflowStub(DisputeCaseWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(DisputeCaseWorkflow.TASK_QUEUE)
                .setWorkflowId(WORKFLOW_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    // --- 1. happy path ---------------------------------------------------------------------

    @Test
    @DisplayName("happy path: no gaps, deterministic short-circuit, gate allows, submit and close")
    void happyPath() {
        activities.withBlockingGaps(0)
                .withAiAdmitted(false)
                .withGateDecision(SafetyDecision.ALLOW)
                .withRecommendedAction(RecommendedAction.PREPARE_REPRESENTMENT);

        CaseOutcome outcome = run();

        assertThat(outcome.resolution()).isEqualTo(CaseResolution.SUBMITTED_AWAITING_OUTCOME);
        assertThat(outcome.caseStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(outcome.networkReference()).isEqualTo("SIMNET-TEST0001");
        assertThat(outcome.packageVersion()).isEqualTo(1);
        assertThat(outcome.assessmentRounds()).isEqualTo(1);

        // All twelve steps ran, exactly once each, in order.
        assertThat(activities.calls())
                .containsSubsequence("openCase", "gatherEvidence", "detectGaps",
                        "runAdmissionControl", "investigate", "validateAndGate",
                        "prepareRepresentmentPackage", "submitRepresentment", "closeCase");
        assertThat(activities.prepareCount()).isEqualTo(1);
        assertThat(activities.submitCount()).isEqualTo(1);

        // Step 4 was skipped: nothing blocking was missing, so nothing was waited for.
        assertThat(activities.gatherCount()).isEqualTo(1);
        // These are the events the WORKFLOW publishes. CaseOpened and CaseClosed are emitted by the
        // openCase and closeCase activities themselves, so they are not visible through this fake.
        assertThat(activities.publishedEvents())
                .contains(EventType.CaseEvidenceAttached, EventType.CaseInvestigated,
                        EventType.CasePrepared, EventType.CaseSubmitted)
                .doesNotContain(EventType.CaseEscalated);

        // Step 11 ticked until the follow-up ceiling, then step 12 closed the case.
        assertThat(activities.closeRequests()).hasSize(1);
        assertThat(activities.closeRequests().get(0).compensating()).isFalse();
    }

    // --- 2. missing evidence, then the signal arrives ----------------------------------------

    @Test
    @DisplayName("missing evidence: step 4 waits, evidenceArrived closes the gap, case proceeds")
    void missingEvidenceThenSignal() {
        activities.withBlockingGaps(1)
                .withAiAdmitted(false)
                .withGateDecision(SafetyDecision.ALLOW);

        // Two hours into the seven-day wait the merchant uploads the delivery proof.
        testEnv.registerDelayedCallback(Duration.ofHours(2), () -> {
            activities.closeAllGaps();
            workflow.evidenceArrived(new EvidenceArrivedSignal("EV-0003",
                    EvidenceType.DELIVERY_PROOF, "evt-1", Instant.parse("2026-08-26T12:00:00Z")));
            // A redelivery of the same evidence id must NOT wake the wait a second time.
            workflow.evidenceArrived(new EvidenceArrivedSignal("EV-0003",
                    EvidenceType.DELIVERY_PROOF, "evt-1", Instant.parse("2026-08-26T12:00:00Z")));
        });

        CaseOutcome outcome = run();

        assertThat(outcome.resolution()).isEqualTo(CaseResolution.SUBMITTED_AWAITING_OUTCOME);
        assertThat(outcome.assessmentRounds()).isEqualTo(1);

        // Re-gathered after the signal: once before the wait, once when it was woken.
        assertThat(activities.gatherCount()).isEqualTo(2);
        assertThat(activities.detectCount()).isEqualTo(2);
        assertThat(activities.submitCount()).isEqualTo(1);

        // The case was parked in AWAITING_EVIDENCE before it moved on.
        assertThat(activities.publishedStatuses()).contains(CaseStatus.AWAITING_EVIDENCE);
        assertThat(outcome.completedSteps())
                .anyMatch(step -> step.startsWith("awaitMissingEvidence: blocking gaps closed"));
    }

    // --- 3. human rejection -------------------------------------------------------------------

    @Test
    @DisplayName("human rejection: gate asks for review, reviewer rejects, nothing is submitted")
    void humanRejection() {
        activities.withBlockingGaps(0)
                .withAiAdmitted(true)
                .withClassification(InvestigationClassification.WEAK, 0.62d)
                .withGateDecision(SafetyDecision.ALLOW_WITH_REVIEW,
                        "confidence 0.62 is below the unattended threshold 0.95");

        testEnv.registerDelayedCallback(Duration.ofHours(1), () ->
                workflow.humanDecision(HumanDecision.reject("ops@laserpay.test",
                        "delivery proof is for a different address")));

        CaseOutcome outcome = run();

        assertThat(outcome.resolution()).isEqualTo(CaseResolution.REJECTED_BY_HUMAN);
        assertThat(outcome.caseStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(outcome.reason()).contains("different address");

        // The whole point: a rejected case never reaches steps 9, 10 or 11.
        assertThat(activities.prepareCount()).isZero();
        assertThat(activities.submitCount()).isZero();
        assertThat(activities.calls())
                .doesNotContain("prepareRepresentmentPackage", "submitRepresentment");
        assertThat(activities.publishedStatuses()).contains(CaseStatus.AWAITING_APPROVAL);

        // The dispute is asked to move to LOST, which is DisputeService's decision to accept.
        assertThat(activities.closeRequests().get(0).targetDisputeStatus())
                .isEqualTo(DisputeStatus.LOST);
    }

    // --- 4. AI denied by the safety gate -------------------------------------------------------

    @Test
    @DisplayName("safety gate DENY: AI proposal is never acted on, case escalates and expires")
    void aiDeniedBySafetyGate() {
        activities.withBlockingGaps(0)
                .withAiAdmitted(true)
                .withClassification(InvestigationClassification.DEFENDABLE, 0.99d)
                .withRecommendedAction(RecommendedAction.PREPARE_REPRESENTMENT)
                .withGateDecision(SafetyDecision.DENY,
                        "evidence EV-9999 is not linked to this case's transaction");

        // Nobody answers: the 48h approval window expires, the case escalates, the 72h
        // escalation window expires too.
        CaseOutcome outcome = run();

        assertThat(outcome.resolution()).isEqualTo(CaseResolution.ESCALATION_EXPIRED);
        assertThat(outcome.caseStatus()).isEqualTo(CaseStatus.CLOSED);

        // A DENIED proposal must never reach package assembly or submission, whatever it claimed.
        assertThat(activities.prepareCount()).isZero();
        assertThat(activities.submitCount()).isZero();
        assertThat(outcome.networkReference()).isNull();

        // The escalation is visible outside Temporal.
        assertThat(activities.publishedEvents()).contains(EventType.CaseEscalated);
        assertThat(outcome.completedSteps())
                .anyMatch(step -> step.startsWith("awaitHumanApproval: escalated after"))
                .anyMatch(step -> step.contains("escalation window expired"));
        assertThat(activities.closeRequests().get(0).targetDisputeStatus())
                .isEqualTo(DisputeStatus.AWAITING_HUMAN_REVIEW);
    }

    // --- 5. queries and terminal dispute --------------------------------------------------------

    @Test
    @DisplayName("queries report the live phase, and a terminal disputeUpdated ends follow-up")
    void queriesAndTerminalDispute() {
        activities.withBlockingGaps(1).withGateDecision(SafetyDecision.ALLOW);

        // Queries are captured, not asserted, inside the callback: an assertion failure on a
        // Temporal-managed thread would not fail the test. They are checked after the run.
        AtomicReference<CaseProgress> progressWhileWaiting = new AtomicReference<>();
        AtomicReference<CaseState> stateWhileWaiting = new AtomicReference<>();

        testEnv.registerDelayedCallback(Duration.ofHours(1), () -> {
            progressWhileWaiting.set(workflow.getProgress());
            stateWhileWaiting.set(workflow.getCaseState());

            activities.closeAllGaps();
            workflow.evidenceArrived(EvidenceArrivedSignal.of("EV-0004",
                    EvidenceType.DELIVERY_PROOF));
        });

        // Once submitted, the network answers: the follow-up loop must stop on a terminal status.
        testEnv.registerDelayedCallback(Duration.ofHours(4), () ->
                workflow.disputeUpdated(new DisputeUpdatedSignal(DisputeStatus.WON, "evt-won",
                        "network found for the merchant", Instant.parse("2026-08-27T00:00:00Z"))));

        CaseOutcome outcome = run();

        CaseProgress progress = progressWhileWaiting.get();
        assertThat(progress).isNotNull();
        assertThat(progress.phase()).isEqualTo(CasePhase.AWAITING_EVIDENCE);
        assertThat(progress.step()).isEqualTo(4);
        assertThat(progress.totalSteps()).isEqualTo(CasePhase.TOTAL_STEPS);
        assertThat(progress.waiting()).isTrue();
        assertThat(progress.waitingFor()).contains("evidenceArrived");

        CaseState state = stateWhileWaiting.get();
        assertThat(state).isNotNull();
        assertThat(state.caseStatus()).isEqualTo(CaseStatus.AWAITING_EVIDENCE);
        assertThat(state.blockingGapCount()).isEqualTo(1);
        assertThat(state.disputeAmount()).isEqualTo(FakeCaseActivities.AMOUNT);

        assertThat(outcome.resolution()).isEqualTo(CaseResolution.SUBMITTED_AND_RESOLVED);
        assertThat(outcome.disputeStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(activities.submitCount()).isEqualTo(1);
    }

    // --- helpers --------------------------------------------------------------------------------

    private CaseOutcome run() {
        WorkflowClient.start(workflow::run, input());
        return WorkflowStub.fromTyped(workflow).getResult(CaseOutcome.class);
    }

    private static DisputeCaseInput input() {
        return DisputeCaseInput.start(
                FakeCaseActivities.CASE_ID,
                FakeCaseActivities.DISPUTE_ID,
                FakeCaseActivities.MERCHANT_ID,
                FakeCaseActivities.TRANSACTION_ID,
                DisputeReasonCode.GOODS_NOT_RECEIVED,
                FakeCaseActivities.AMOUNT,
                Instant.parse("2026-08-26T09:00:00Z"),
                // TestWorkflowEnvironment starts its clock at the real wall time, so the deadline is
                // set far enough out that no scenario is ever interrupted by it, whenever the suite
                // happens to run. Deadline behaviour itself is covered by checkInterrupts, not here.
                Instant.parse("2099-12-31T00:00:00Z"),
                "corr-test-0001",
                "evt-created-0001",
                "case-orchestrator-service",
                TIMERS);
    }
}
