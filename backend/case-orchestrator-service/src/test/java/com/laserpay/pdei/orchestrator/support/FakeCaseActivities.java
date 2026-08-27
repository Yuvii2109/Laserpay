package com.laserpay.pdei.orchestrator.support;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.orchestrator.activity.CaseActivities;
import com.laserpay.pdei.orchestrator.model.AdmissionOutcome;
import com.laserpay.pdei.orchestrator.model.CaseEventCommand;
import com.laserpay.pdei.orchestrator.model.CaseRef;
import com.laserpay.pdei.orchestrator.model.CloseCaseRequest;
import com.laserpay.pdei.orchestrator.model.CloseCaseResult;
import com.laserpay.pdei.orchestrator.model.EvidenceReport;
import com.laserpay.pdei.orchestrator.model.GapReport;
import com.laserpay.pdei.orchestrator.model.GateOutcome;
import com.laserpay.pdei.orchestrator.model.GateRequest;
import com.laserpay.pdei.orchestrator.model.InvestigationOutcome;
import com.laserpay.pdei.orchestrator.model.InvestigationRequest;
import com.laserpay.pdei.orchestrator.model.OpenCaseRequest;
import com.laserpay.pdei.orchestrator.model.OpenCaseResult;
import com.laserpay.pdei.orchestrator.model.PackageResult;
import com.laserpay.pdei.orchestrator.model.PreparePackageRequest;
import com.laserpay.pdei.orchestrator.model.SubmissionReceipt;
import com.laserpay.pdei.orchestrator.model.SubmitRequest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A hand-written {@link CaseActivities} double for {@code TestWorkflowEnvironment}.
 *
 * <p>Hand-written rather than mocked on purpose: the workflow calls the same activity several times
 * across an execution and the interesting scenarios are about how the answers <em>change</em>
 * between calls - gaps closing when evidence arrives, a gate denying an AI proposal. A stateful
 * fake expresses that far more legibly than a stack of stubbed returns, and it lets a test mutate
 * the world from a delayed callback exactly as the real world would change.</p>
 *
 * <p>Every mutable field is thread-safe: the workflow runs on Temporal's threads while the test
 * mutates from the main thread and from {@code registerDelayedCallback}.</p>
 */
public class FakeCaseActivities implements CaseActivities {

    public static final String CASE_ID = "CASE-TEST0001";
    public static final String DISPUTE_ID = "DSP-TEST0001";
    public static final String MERCHANT_ID = "MER-TEST0001";
    public static final String TRANSACTION_ID = "TX-TEST0001";
    public static final Money AMOUNT = Money.of(1_299_900L, "INR");

    // --- scenario knobs, all settable from a test ------------------------------------------

    /** Number of blocking gaps {@code detectGaps} reports. Set to 0 to let step 4 be skipped. */
    private final AtomicInteger blockingGaps = new AtomicInteger(0);
    /** Whether admission control admits the case to the model. */
    private final AtomicBoolean admitAi = new AtomicBoolean(false);
    /** What {@code validateAndGate} decides. */
    private volatile SafetyDecision gateDecision = SafetyDecision.ALLOW;
    /** What the investigation recommends. */
    private volatile RecommendedAction recommendedAction = RecommendedAction.PREPARE_REPRESENTMENT;
    private volatile InvestigationClassification classification =
            InvestigationClassification.DEFENDABLE;
    private volatile double confidence = 0.97d;
    /** Gate reasons reported when the decision is not ALLOW. */
    private volatile List<String> gateReasons = List.of();

    // --- recorded calls ---------------------------------------------------------------------

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<EventType> publishedEvents = new CopyOnWriteArrayList<>();
    private final List<CaseStatus> publishedStatuses = new CopyOnWriteArrayList<>();
    private final List<CloseCaseRequest> closeRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger gatherCount = new AtomicInteger();
    private final AtomicInteger detectCount = new AtomicInteger();
    private final AtomicInteger investigateCount = new AtomicInteger();
    private final AtomicInteger prepareCount = new AtomicInteger();
    private final AtomicInteger submitCount = new AtomicInteger();
    private final AtomicInteger packageVersion = new AtomicInteger();

    // --- CaseActivities ---------------------------------------------------------------------

    @Override
    public OpenCaseResult openCase(OpenCaseRequest request) {
        calls.add("openCase");
        return new OpenCaseResult(request.caseId(), request.disputeId(), request.merchantId(),
                request.transactionId(), CaseStatus.CREATED, DisputeStatus.EVIDENCE_GATHERING,
                DisputeReasonCode.GOODS_NOT_RECEIVED, request.disputeAmount(), request.openedAt(),
                request.deadlineAt(), false);
    }

    @Override
    public EvidenceReport gatherEvidence(CaseRef ref) {
        calls.add("gatherEvidence");
        gatherCount.incrementAndGet();
        boolean complete = blockingGaps.get() == 0;
        return new EvidenceReport(ref.caseId(), ref.transactionId(),
                List.of("EV-0001", "EV-0002"), 2, 2,
                complete ? 96 : 62,
                complete ? ReadinessBand.READY : ReadinessBand.AT_RISK,
                complete, complete ? 0 : 1,
                complete ? 0.96d : 0.46d, "POLV-TEST");
    }

    @Override
    public GapReport detectGaps(CaseRef ref) {
        calls.add("detectGaps");
        detectCount.incrementAndGet();
        int blocking = blockingGaps.get();
        if (blocking == 0) {
            return GapReport.empty(ref.caseId(), ref.transactionId());
        }
        List<GapReport.Gap> gaps = List.of(new GapReport.Gap("GAP-1", GapType.MISSING,
                EvidenceType.DELIVERY_PROOF, GapSeverity.CRITICAL, "no delivery proof on file"));
        return new GapReport(ref.caseId(), ref.transactionId(), gaps, blocking, blocking, 0, 1,
                List.of(EvidenceType.DELIVERY_PROOF));
    }

    @Override
    public AdmissionOutcome runAdmissionControl(CaseRef ref, String idempotencyToken) {
        calls.add("runAdmissionControl");
        boolean admit = admitAi.get();
        return new AdmissionOutcome(ref.caseId(), admit, admit ? 71 : 20,
                admit ? "admitted with priority 71" : "all mandatory requirements satisfied",
                admit ? "NONE" : "ALL_REQUIREMENTS_SATISFIED",
                admit ? null : RecommendedAction.PREPARE_REPRESENTMENT,
                0.13d, 0.5d, admit ? 0.5d : 0.0d, admit ? 0.4d : 0.96d);
    }

    @Override
    public InvestigationOutcome investigate(InvestigationRequest request) {
        calls.add("investigate");
        int n = investigateCount.incrementAndGet();
        boolean aiUsed = request.useAi();
        return new InvestigationOutcome("INV-TEST" + n, request.ref().caseId(), classification,
                confidence, recommendedAction, List.of("EV-0001"), List.of(), 0,
                aiUsed ? "model reasoning" : "deterministic assessment", aiUsed,
                aiUsed ? "gemini" : "deterministic",
                aiUsed ? "gemini-3.5-flash-lite" : "pdei-deterministic-v1", 120L);
    }

    @Override
    public GateOutcome validateAndGate(GateRequest request) {
        calls.add("validateAndGate");
        return new GateOutcome(request.ref().caseId(), request.investigationId(), gateDecision,
                recommendedAction, gateReasons, gateDecision == SafetyDecision.DENY
                        ? List.of("EV-9999 is not linked to this transaction") : List.of(),
                96, false);
    }

    @Override
    public PackageResult prepareRepresentmentPackage(PreparePackageRequest request) {
        calls.add("prepareRepresentmentPackage");
        prepareCount.incrementAndGet();
        int version = packageVersion.incrementAndGet();
        return new PackageResult(request.ref().caseId(), "PKG-TEST" + version, version,
                MERCHANT_ID + "/" + request.ref().caseId() + "/representment-"
                        + request.ref().caseId() + "-v" + version + ".zip",
                "0f".repeat(32), 4096L, 2, 96, "POLV-TEST", Instant.parse("2026-08-26T10:00:00Z"));
    }

    @Override
    public SubmissionReceipt submitRepresentment(SubmitRequest request) {
        calls.add("submitRepresentment");
        submitCount.incrementAndGet();
        return new SubmissionReceipt(request.ref().caseId(), "SUB-TEST0001", "SIMNET-TEST0001",
                "SIMULATED_NETWORK", true, true, "accepted by the simulated network",
                request.packageResult().packageVersion(), request.packageResult().bundleObjectKey(),
                request.packageResult().bundleSha256(),
                MERCHANT_ID + "/" + request.ref().caseId() + "/submission-"
                        + request.ref().caseId() + "-v" + request.packageResult().packageVersion()
                        + ".json",
                Instant.parse("2026-08-26T10:05:00Z"));
    }

    @Override
    public CloseCaseResult closeCase(CloseCaseRequest request) {
        calls.add("closeCase");
        closeRequests.add(request);
        CaseStatus status = request.targetCaseStatus() == null
                ? CaseStatus.CLOSED : request.targetCaseStatus();
        return new CloseCaseResult(request.ref().caseId(), status, request.targetDisputeStatus(),
                request.targetDisputeStatus() != null, request.resolution(),
                Instant.parse("2026-08-26T11:00:00Z"));
    }

    @Override
    public void publishCaseEvent(CaseEventCommand command) {
        calls.add("publishCaseEvent");
        if (command.eventType() != null) {
            publishedEvents.add(command.eventType());
        }
        if (command.status() != null) {
            publishedStatuses.add(command.status());
        }
    }

    // --- scenario control --------------------------------------------------------------------

    public FakeCaseActivities withBlockingGaps(int count) {
        blockingGaps.set(count);
        return this;
    }

    public FakeCaseActivities withAiAdmitted(boolean admitted) {
        admitAi.set(admitted);
        return this;
    }

    public FakeCaseActivities withGateDecision(SafetyDecision decision, String... reasons) {
        this.gateDecision = decision;
        this.gateReasons = List.of(reasons);
        return this;
    }

    public FakeCaseActivities withRecommendedAction(RecommendedAction action) {
        this.recommendedAction = action;
        return this;
    }

    public FakeCaseActivities withClassification(InvestigationClassification value, double confidence) {
        this.classification = value;
        this.confidence = confidence;
        return this;
    }

    /** Simulates the merchant uploading what was missing. */
    public void closeAllGaps() {
        blockingGaps.set(0);
    }

    // --- assertions --------------------------------------------------------------------------

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<EventType> publishedEvents() {
        return List.copyOf(publishedEvents);
    }

    public List<CaseStatus> publishedStatuses() {
        return List.copyOf(publishedStatuses);
    }

    public List<CloseCaseRequest> closeRequests() {
        return List.copyOf(closeRequests);
    }

    public int gatherCount() {
        return gatherCount.get();
    }

    public int detectCount() {
        return detectCount.get();
    }

    public int investigateCount() {
        return investigateCount.get();
    }

    public int prepareCount() {
        return prepareCount.get();
    }

    public int submitCount() {
        return submitCount.get();
    }
}
