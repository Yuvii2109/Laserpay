package com.laserpay.pdei.orchestrator.activity;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.ai.AdmissionController;
import com.laserpay.pdei.core.ai.AdmissionDecision;
import com.laserpay.pdei.core.ai.AdmissionRequest;
import com.laserpay.pdei.core.ai.AiReasoningClient;
import com.laserpay.pdei.core.ai.DeterministicInvestigator;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.dispute.CaseAssemblyService;
import com.laserpay.pdei.core.dispute.DisputeService;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.HistoricalContext;
import com.laserpay.pdei.core.model.InvestigationContext;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.PackageManifest;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import com.laserpay.pdei.core.model.RequirementView;
import com.laserpay.pdei.core.model.SafetyVerdict;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.core.safety.GateInput;
import com.laserpay.pdei.core.safety.SafetyGate;
import com.laserpay.pdei.core.spi.CaseEvidenceRecord;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.InvestigationRecord;
import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.core.timeline.TimelineService;
import com.laserpay.pdei.orchestrator.model.AdmissionOutcome;
import com.laserpay.pdei.orchestrator.model.CaseEventCommand;
import com.laserpay.pdei.orchestrator.model.CasePhase;
import com.laserpay.pdei.orchestrator.model.CaseRef;
import com.laserpay.pdei.orchestrator.model.CaseResolution;
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
import com.laserpay.pdei.orchestrator.persistence.CaseRow;
import com.laserpay.pdei.orchestrator.persistence.CaseWriter;
import com.laserpay.pdei.orchestrator.submission.NetworkSubmissionRequest;
import com.laserpay.pdei.orchestrator.submission.NetworkSubmissionResult;
import com.laserpay.pdei.orchestrator.submission.NetworkSubmitter;
import com.laserpay.pdei.orchestrator.workflow.DisputeCaseWorkflow;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only place where the dispute workflow touches the world.
 *
 * <p>Every method is a thin sequencing layer over {@code evidence-core}: readiness comes from
 * {@code ReadinessEngine}, admission from {@code AdmissionController}, the verdict from
 * {@code SafetyGate}, the package from {@code CaseAssemblyService}, the dispute transition from
 * {@code DisputeService}. This class decides <em>when</em>, never <em>what</em>.</p>
 *
 * <p><b>The AI never mutates financial state.</b> {@link #investigate} obtains a proposal and stores
 * it; nothing acts on it until {@link #validateAndGate} has judged it, and when the gate denies a
 * proposal this class strips its narrative from the stored record so that no later step - package
 * assembly included - can quote a rejected claim.</p>
 *
 * <p><b>Idempotency.</b> Reads are naturally repeatable and writes are whole-value upserts. The four
 * activities that are not naturally idempotent go through {@link ActivityMemo} under a
 * workflow-supplied deterministic token.</p>
 */
@Component
@ActivityImpl(taskQueues = DisputeCaseWorkflow.TASK_QUEUE)
public class CaseActivitiesImpl implements CaseActivities {

    private static final Logger log = LoggerFactory.getLogger(CaseActivitiesImpl.class);

    private static final String ENTITY_CASE = "CASE";
    private static final String SERVICE_NAME = "case-orchestrator-service";
    /** Key shape for the submission receipt written alongside the bundle (see context.md). */
    private static final String RECEIPT_KEY_TEMPLATE = "%s/%s/submission-%s-v%d.json";

    private final CaseWriter caseWriter;
    private final CaseRepositoryPort cases;
    private final EvidenceRepositoryPort evidenceRepository;
    private final ReadinessEngine readinessEngine;
    private final PolicyEngine policyEngine;
    private final AdmissionController admissionController;
    private final AiReasoningClient aiClient;
    private final DeterministicInvestigator deterministicInvestigator;
    private final SafetyGate safetyGate;
    private final CaseAssemblyService caseAssembly;
    private final DisputeService disputeService;
    private final TimelineService timelineService;
    private final ObjectStore objectStore;
    private final EventPublisherPort publisher;
    private final AuditRecorder audit;
    private final NetworkSubmitter networkSubmitter;
    private final ActivityMemo memo;
    private final Clocks clock;
    private final MeterRegistry meterRegistry;

    @SuppressWarnings("java:S107") // an orchestrator legitimately depends on the whole domain engine
    public CaseActivitiesImpl(CaseWriter caseWriter, CaseRepositoryPort cases,
                              EvidenceRepositoryPort evidenceRepository, ReadinessEngine readinessEngine,
                              PolicyEngine policyEngine, AdmissionController admissionController,
                              AiReasoningClient aiClient, DeterministicInvestigator deterministicInvestigator,
                              SafetyGate safetyGate, CaseAssemblyService caseAssembly,
                              DisputeService disputeService, TimelineService timelineService,
                              ObjectStore objectStore, EventPublisherPort publisher, AuditRecorder audit,
                              NetworkSubmitter networkSubmitter, ActivityMemo memo, Clocks clock,
                              MeterRegistry meterRegistry) {
        this.caseWriter = caseWriter;
        this.cases = cases;
        this.evidenceRepository = evidenceRepository;
        this.readinessEngine = readinessEngine;
        this.policyEngine = policyEngine;
        this.admissionController = admissionController;
        this.aiClient = aiClient;
        this.deterministicInvestigator = deterministicInvestigator;
        this.safetyGate = safetyGate;
        this.caseAssembly = caseAssembly;
        this.disputeService = disputeService;
        this.timelineService = timelineService;
        this.objectStore = objectStore;
        this.publisher = publisher;
        this.audit = audit;
        this.networkSubmitter = networkSubmitter;
        this.memo = memo;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    // --- step 1 ---------------------------------------------------------------------------------

    @Override
    public OpenCaseResult openCase(OpenCaseRequest request) {
        Instant now = clock.now();
        DisputeView dispute = requireDispute(request.disputeId());

        // A case row may already exist: a redelivered DisputeCreated, an activity retry, or a case
        // opened by an operator before the event arrived. Adopt it rather than opening a second one.
        Optional<CaseRow> existing = caseWriter.findByDispute(dispute.disputeId());
        String caseId = existing.map(CaseRow::caseId).orElse(request.caseId());
        boolean created = false;

        if (existing.isEmpty()) {
            created = caseWriter.insertIfAbsent(caseId, dispute.disputeId(), dispute.merchantId(),
                    dispute.transactionId(), dispute.amount(),
                    request.openedAt() == null ? dispute.openedAt() : request.openedAt(),
                    dispute.deadlineAt(), request.workflowId(), request.runId(), now);
        }
        caseWriter.bindWorkflow(caseId, request.workflowId(), request.runId(), now);
        caseWriter.updateStatus(caseId, CaseStatus.CREATED, CasePhase.OPENING.percent(), now);

        DisputeStatus disputeStatus = advanceDispute(dispute, DisputeStatus.EVIDENCE_GATHERING,
                request.actor(), "case " + caseId + " opened");

        audit.record(AuditCommand.of(ENTITY_CASE, caseId, dispute.merchantId(), "CASE_OPENED",
                        request.actor(), ActorType.SYSTEM)
                .withCorrelationId(request.correlationId())
                .withAfter(Map.<String, Object>of("caseId", caseId, "disputeId", dispute.disputeId(),
                        "workflowId", String.valueOf(request.workflowId()),
                        "adopted", !created)));

        publish(EventType.CaseOpened, caseId, dispute.merchantId(),
                Map.<String, Object>of("disputeId", dispute.disputeId(),
                        "transactionId", dispute.transactionId(),
                        "reasonCode", dispute.reasonCode().name(),
                        "amountMinor", dispute.amount() == null ? 0L : dispute.amount().amountMinor(),
                        "currency", dispute.amount() == null ? "" : dispute.amount().currency(),
                        "workflowId", String.valueOf(request.workflowId()),
                        "adopted", !created),
                "CaseOpened:" + caseId, request.correlationId(), null, now);

        log.info("openCase {} for dispute {} ({}), created={}", caseId, dispute.disputeId(),
                dispute.reasonCode(), created);

        return new OpenCaseResult(caseId, dispute.disputeId(), dispute.merchantId(),
                dispute.transactionId(), CaseStatus.CREATED, disputeStatus, dispute.reasonCode(),
                dispute.amount(), dispute.openedAt(), dispute.deadlineAt(), !created);
    }

    // --- step 2 ---------------------------------------------------------------------------------

    @Override
    public EvidenceReport gatherEvidence(CaseRef ref) {
        Instant now = clock.now();
        DisputeView dispute = requireDispute(ref.disputeId());
        PolicyView policy = policyEngine.applicablePolicy(ref.merchantId(), dispute.reasonCode());
        ReadinessSnapshot snapshot = readinessEngine.compute(ref.transactionId(), dispute.reasonCode());

        List<EvidenceView> all = evidenceRepository.findByTransactionId(ref.transactionId());
        List<EvidenceView> selected = caseAssembly.selectEvidence(ref.transactionId(), policy);

        // Pin the selection to the case. replaceCaseEvidence is a full replacement, so re-running
        // this activity converges on the same set instead of accumulating rows.
        List<CaseEvidenceRecord> pinned = new ArrayList<>(selected.size());
        int position = 0;
        for (EvidenceView view : selected) {
            pinned.add(new CaseEvidenceRecord(ref.caseId(), view.evidenceId(),
                    policy.strengthOf(view.type()), ++position, view.sha256(), now));
        }
        cases.replaceCaseEvidence(ref.caseId(), pinned);

        caseWriter.updateReadiness(ref.caseId(), snapshot.score(), snapshot.band(), now);
        caseWriter.updateStatus(ref.caseId(), CaseStatus.ASSEMBLING,
                CasePhase.GATHERING_EVIDENCE.percent(), now);

        int usable = (int) all.stream().filter(EvidenceView::isUsable).count();
        log.info("gatherEvidence case {}: {} artifact(s), {} usable, {} selected, readiness {} ({})",
                ref.caseId(), all.size(), usable, selected.size(), snapshot.score(), snapshot.band());

        return new EvidenceReport(ref.caseId(), ref.transactionId(),
                selected.stream().map(EvidenceView::evidenceId).toList(),
                all.size(), usable, snapshot.score(), snapshot.band(),
                snapshot.allMandatorySatisfied(), snapshot.unsatisfiedMandatory().size(),
                snapshot.deterministicConfidence(), snapshot.policyVersionId());
    }

    // --- step 3 ---------------------------------------------------------------------------------

    @Override
    public GapReport detectGaps(CaseRef ref) {
        DisputeView dispute = requireDispute(ref.disputeId());
        ReadinessSnapshot snapshot = readinessEngine.compute(ref.transactionId(), dispute.reasonCode());

        List<GapReport.Gap> gaps = snapshot.gaps().stream()
                .map(gap -> new GapReport.Gap(gap.gapId(), gap.type(), gap.evidenceType(),
                        gap.severity(), gap.detail()))
                .toList();
        int blocking = (int) snapshot.gaps().stream().filter(ReadinessGap::isBlocking).count();
        List<EvidenceType> missingMandatory = snapshot.unsatisfiedMandatory().stream()
                .map(RequirementView::type)
                .toList();

        log.info("detectGaps case {}: {} gap(s), {} blocking, {} contradiction(s), {} unsatisfied mandatory",
                ref.caseId(), gaps.size(), blocking, snapshot.contradictions().size(),
                missingMandatory.size());

        return new GapReport(ref.caseId(), ref.transactionId(), gaps, gaps.size(), blocking,
                snapshot.contradictions().size(), missingMandatory.size(), missingMandatory);
    }

    // --- step 5 ---------------------------------------------------------------------------------

    @Override
    public AdmissionOutcome runAdmissionControl(CaseRef ref, String idempotencyToken) {
        return memo.remember(ref.caseId(), idempotencyToken, AdmissionOutcome.class,
                () -> doRunAdmissionControl(ref));
    }

    private AdmissionOutcome doRunAdmissionControl(CaseRef ref) {
        Instant now = clock.now();
        DisputeView dispute = requireDispute(ref.disputeId());
        ReadinessSnapshot snapshot = readinessEngine.compute(ref.transactionId(), dispute.reasonCode());
        int evidenceCount = (int) evidenceRepository.findByTransactionId(ref.transactionId()).stream()
                .filter(EvidenceView::isUsable)
                .count();

        AdmissionDecision decision = admissionController.decide(new AdmissionRequest(
                ref.caseId(), ref.merchantId(), ref.transactionId(), dispute.reasonCode(),
                dispute.amount(), dispute.deadlineAt(),
                snapshot.contradictions().size(), snapshot.gaps().size(), evidenceCount,
                snapshot.unsatisfiedMandatory().size(), snapshot.deterministicConfidence(), now));

        caseWriter.updateStatus(ref.caseId(), CaseStatus.INVESTIGATING,
                CasePhase.ADMISSION_CONTROL.percent(), now);

        log.info("runAdmissionControl case {}: admit={} priority={} shortCircuit={} ({})",
                ref.caseId(), decision.admit(), decision.priority(), decision.shortCircuit(),
                decision.reason());

        return new AdmissionOutcome(ref.caseId(), decision.admit(), decision.priority(),
                decision.reason(), decision.shortCircuit().name(), decision.deterministicAction(),
                decision.financialImpact(), decision.deadlineUrgency(), decision.ambiguityScore(),
                decision.deterministicConfidence());
    }

    // --- step 6 ---------------------------------------------------------------------------------

    @Override
    public InvestigationOutcome investigate(InvestigationRequest request) {
        return memo.remember(request.ref().caseId(), request.idempotencyToken(),
                InvestigationOutcome.class, () -> doInvestigate(request));
    }

    private InvestigationOutcome doInvestigate(InvestigationRequest request) {
        CaseRef ref = request.ref();
        Instant startedAt = clock.now();
        DisputeView dispute = requireDispute(ref.disputeId());
        ReadinessSnapshot snapshot = readinessEngine.compute(ref.transactionId(), dispute.reasonCode());
        PolicyView policy = policyEngine.applicablePolicy(ref.merchantId(), dispute.reasonCode());

        String investigationId = Ids.investigation();
        InvestigationContext context = buildContext(investigationId, ref, dispute, snapshot, policy);

        InvestigationResult result;
        if (request.useAi() && aiClient.isAvailable()) {
            // HttpAiReasoningClient never throws on a provider failure; it degrades to the
            // deterministic investigator and says so in modelMetadata.
            result = aiClient.investigate(context);
        } else {
            result = deterministicInvestigator.investigate(context);
        }
        if (result == null) {
            result = deterministicInvestigator.investigate(context);
        }
        // The id is ours, not the service's. Never let a remote system choose a primary key.
        result = withInvestigationId(result, investigationId);

        boolean aiUsed = result.modelMetadata() != null && !result.modelMetadata().isDeterministic();
        long latencyMs = Duration.between(startedAt, clock.now()).toMillis();
        Instant completedAt = clock.now();

        cases.saveInvestigation(new InvestigationRecord(
                investigationId, ref.caseId(), ref.disputeId(), ref.merchantId(), ref.transactionId(),
                result.classification(), result.confidence(), result.recommendedAction(),
                null, providerOf(result), modelOf(result), latencyMs,
                promptTokens(result), completionTokens(result), attempt(result),
                result.reasoningSummary(), result.narrative(), Json.write(result), null,
                startedAt, completedAt));

        caseWriter.updateAssessment(ref.caseId(), result.recommendedAction(), null, completedAt);
        caseWriter.updateStatus(ref.caseId(), CaseStatus.INVESTIGATING,
                CasePhase.INVESTIGATING.percent(), completedAt);

        audit.record(AuditCommand.of("INVESTIGATION", investigationId, ref.merchantId(),
                        aiUsed ? "INVESTIGATION_AI" : "INVESTIGATION_DETERMINISTIC",
                        SERVICE_NAME, aiUsed ? ActorType.AI_SERVICE : ActorType.SYSTEM)
                .withCorrelationId(ref.correlationId())
                .withAfter(result));

        log.info("investigate case {}: {} confidence={} action={} provider={} ({} ms)", ref.caseId(),
                result.classification(), result.confidence(), result.recommendedAction(),
                providerOf(result), latencyMs);

        return new InvestigationOutcome(investigationId, ref.caseId(), result.classification(),
                result.confidence(), result.recommendedAction(), result.supportingEvidence(),
                result.missingEvidence(), result.contradictions().size(), result.reasoningSummary(),
                aiUsed, providerOf(result), modelOf(result), latencyMs);
    }

    // --- step 7 ---------------------------------------------------------------------------------

    @Override
    public GateOutcome validateAndGate(GateRequest request) {
        return memo.remember(request.ref().caseId(), request.idempotencyToken(), GateOutcome.class,
                () -> doValidateAndGate(request));
    }

    private GateOutcome doValidateAndGate(GateRequest request) {
        CaseRef ref = request.ref();
        Instant now = clock.now();
        InvestigationRecord record = cases.findInvestigation(request.investigationId())
                .orElseThrow(() -> new NotFoundException("INVESTIGATION", request.investigationId()));
        if (record.resultJson() == null || record.resultJson().isBlank()) {
            throw new ValidationException("investigation " + request.investigationId()
                    + " has no stored result to validate");
        }
        InvestigationResult result = Json.read(record.resultJson(), InvestigationResult.class);

        DisputeView dispute = requireDispute(ref.disputeId());
        ReadinessSnapshot snapshot = readinessEngine.compute(ref.transactionId(), dispute.reasonCode());
        PolicyView policy = policyEngine.applicablePolicy(ref.merchantId(), dispute.reasonCode());
        boolean pastDeadline = dispute.pastDeadline(now);

        GateInput gateInput = new GateInput(ref.caseId(), ref.disputeId(), ref.transactionId(),
                ref.merchantId(), policy, snapshot, dispute.amount(), pastDeadline);

        SafetyVerdict verdict = request.aiUsed()
                ? safetyGate.evaluate(result, gateInput)
                : safetyGate.evaluateDeterministic(result.recommendedAction(), gateInput);

        // Contract rule 3: an unsupported claim is never quoted again. Removing the narrative from
        // the stored record is what stops CaseAssemblyService embedding a denied proposal in the
        // representment bundle if a human later approves the case on other grounds.
        String storedNarrative = verdict.isDenied() ? null : record.narrative();
        cases.saveInvestigation(new InvestigationRecord(
                record.investigationId(), record.caseId(), record.disputeId(), record.merchantId(),
                record.transactionId(), record.classification(), record.confidence(),
                record.recommendedAction(), verdict.decision(), record.provider(), record.model(),
                record.latencyMs(), record.promptTokens(), record.completionTokens(), record.attempt(),
                record.reasoningSummary(), storedNarrative, record.resultJson(), Json.write(verdict),
                record.startedAt(), record.completedAt()));

        caseWriter.updateAssessment(ref.caseId(), result.recommendedAction(), verdict.decision(), now);

        if (verdict.isDenied()) {
            log.warn("validateAndGate case {}: DENY - {} (unsupported claims: {})", ref.caseId(),
                    verdict.reasons(), verdict.unsupportedClaims());
        } else {
            log.info("validateAndGate case {}: {} - {}", ref.caseId(), verdict.decision(),
                    verdict.reasons());
        }

        return new GateOutcome(ref.caseId(), request.investigationId(), verdict.decision(),
                result.recommendedAction(), verdict.reasons(), verdict.unsupportedClaims(),
                snapshot.score(), pastDeadline);
    }

    // --- step 9 ---------------------------------------------------------------------------------

    @Override
    public PackageResult prepareRepresentmentPackage(PreparePackageRequest request) {
        return memo.remember(request.ref().caseId(), request.idempotencyToken(), PackageResult.class,
                () -> doPreparePackage(request));
    }

    private PackageResult doPreparePackage(PreparePackageRequest request) {
        CaseRef ref = request.ref();
        Instant now = clock.now();
        String actor = request.approvedBy() == null ? SERVICE_NAME : request.approvedBy();

        // Throws PolicyViolationException when nothing verifiable is left to submit; that is
        // non-retryable by design, and the workflow's compensation escalates it to a human.
        PackageManifest manifest = caseAssembly.assemble(ref.caseId(), actor);

        caseWriter.updatePackage(ref.caseId(), manifest.packageVersion(), manifest.bundleObjectKey(), now);
        caseWriter.updateStatus(ref.caseId(), CaseStatus.PREPARED, CasePhase.PREPARING_PACKAGE.percent(),
                now);
        if (request.approvedBy() != null) {
            caseWriter.updateApproval(ref.caseId(), request.approvedBy(), null, now, now);
        }
        advanceDispute(requireDispute(ref.disputeId()), DisputeStatus.REPRESENTMENT_PREPARED, actor,
                "representment package v" + manifest.packageVersion() + " assembled");

        log.info("prepareRepresentmentPackage case {}: v{} with {} artifact(s), bundle {} sha256={}",
                ref.caseId(), manifest.packageVersion(), manifest.items().size(),
                manifest.bundleObjectKey(), manifest.bundleSha256());

        return new PackageResult(ref.caseId(), manifest.manifestId(), manifest.packageVersion(),
                manifest.bundleObjectKey(), manifest.bundleSha256(), manifest.bundleSizeBytes(),
                manifest.items().size(), manifest.readinessScore(), manifest.policyVersionId(),
                manifest.generatedAt());
    }

    // --- step 10 --------------------------------------------------------------------------------

    @Override
    public SubmissionReceipt submitRepresentment(SubmitRequest request) {
        return memo.remember(request.ref().caseId(), request.idempotencyToken(), SubmissionReceipt.class,
                () -> doSubmit(request));
    }

    private SubmissionReceipt doSubmit(SubmitRequest request) {
        CaseRef ref = request.ref();
        PackageResult pkg = request.packageResult();
        if (pkg == null || pkg.bundleObjectKey() == null || pkg.bundleSha256() == null) {
            throw new ValidationException("case " + ref.caseId()
                    + " cannot be submitted without an assembled package");
        }
        // The bundle was written to MinIO by step 9. Refuse to "submit" something that is not there:
        // a missing object means the package and the database have diverged.
        if (!objectStore.exists(Buckets.PACKAGES, pkg.bundleObjectKey())) {
            throw new ValidationException("representment bundle " + pkg.bundleObjectKey()
                    + " is not present in bucket " + Buckets.PACKAGES);
        }

        DisputeView dispute = requireDispute(ref.disputeId());
        NetworkSubmissionResult submission = networkSubmitter.submit(new NetworkSubmissionRequest(
                ref.caseId(), ref.disputeId(), ref.merchantId(), ref.transactionId(),
                dispute.networkCaseRef(), pkg.packageVersion(), pkg.bundleObjectKey(),
                pkg.bundleSha256(), pkg.bundleSizeBytes(), pkg.itemCount(),
                request.submittedBy() == null ? SERVICE_NAME : request.submittedBy()));

        if (!submission.accepted()) {
            // Retryable: contract section 10 gives this ten attempts before the workflow gives up.
            throw new UpstreamUnavailableException(networkSubmitter.name(),
                    submission.statusDetail() == null ? "submission rejected" : submission.statusDetail());
        }

        Instant submittedAt = submission.submittedAt() == null ? clock.now() : submission.submittedAt();
        String receiptKey = RECEIPT_KEY_TEMPLATE.formatted(ref.merchantId(), ref.caseId(),
                ref.caseId(), pkg.packageVersion());

        SubmissionReceipt receipt = new SubmissionReceipt(ref.caseId(), submission.submissionId(),
                submission.networkReference(), submission.submitterName(), submission.simulated(),
                true, submission.statusDetail(), pkg.packageVersion(), pkg.bundleObjectKey(),
                pkg.bundleSha256(), receiptKey, submittedAt);

        // The receipt is written next to the bundle so the submission is reconstructable from object
        // storage alone, exactly like the manifest.
        objectStore.put(Buckets.PACKAGES, receiptKey,
                Json.write(receipt).getBytes(StandardCharsets.UTF_8), "application/json",
                Map.of(Buckets.META_EVIDENCE_ID, ref.caseId(),
                        Buckets.META_VERSION, String.valueOf(pkg.packageVersion())));

        caseWriter.updateStatus(ref.caseId(), CaseStatus.SUBMITTED, CasePhase.SUBMITTING.percent(),
                submittedAt);
        advanceDispute(dispute, DisputeStatus.SUBMITTED, request.submittedBy(),
                "representment submitted as " + submission.networkReference());

        audit.record(AuditCommand.of(ENTITY_CASE, ref.caseId(), ref.merchantId(), "CASE_SUBMITTED",
                        request.submittedBy(), ActorType.SYSTEM)
                .withCorrelationId(ref.correlationId())
                .withAfter(receipt));

        log.info("submitRepresentment case {}: {} via {} (simulated={}), receipt {}", ref.caseId(),
                receipt.networkReference(), receipt.submitterName(), receipt.simulated(), receiptKey);
        return receipt;
    }

    // --- step 12 --------------------------------------------------------------------------------

    @Override
    public CloseCaseResult closeCase(CloseCaseRequest request) {
        CaseRef ref = request.ref();
        Instant now = clock.now();
        CaseStatus target = request.targetCaseStatus() == null
                ? CaseStatus.CLOSED : request.targetCaseStatus();

        caseWriter.markClosed(ref.caseId(), target,
                target == CaseStatus.FAILED ? request.reason() : null, now);

        DisputeStatus finalStatus = null;
        boolean transitioned = false;
        Optional<DisputeView> dispute = disputeService.find(ref.disputeId());
        if (dispute.isPresent()) {
            finalStatus = dispute.get().status();
            if (request.targetDisputeStatus() != null && !dispute.get().isTerminal()) {
                DisputeStatus moved = advanceDispute(dispute.get(), request.targetDisputeStatus(),
                        request.actor(), request.reason());
                transitioned = moved != finalStatus;
                finalStatus = moved;
            }
        }

        audit.record(AuditCommand.of(ENTITY_CASE, ref.caseId(), ref.merchantId(),
                        request.compensating() ? "CASE_COMPENSATED" : "CASE_CLOSED",
                        request.actor(), ActorType.SYSTEM)
                .withCorrelationId(ref.correlationId())
                .withAfter(Map.<String, Object>of("resolution", String.valueOf(request.resolution()),
                        "caseStatus", target.name(),
                        "reason", String.valueOf(request.reason()))));

        publish(EventType.CaseClosed, ref.caseId(), ref.merchantId(),
                Map.<String, Object>of("resolution", String.valueOf(request.resolution()),
                        "caseStatus", target.name(),
                        "disputeStatus", String.valueOf(finalStatus),
                        "reason", String.valueOf(request.reason()),
                        "compensating", request.compensating()),
                "CaseClosed:" + ref.caseId() + ":" + request.resolution(), ref.correlationId(), null,
                now);

        if (target == CaseStatus.FAILED || request.resolution() == CaseResolution.FAILED) {
            countWorkflowFailure();
        }

        log.info("closeCase {}: {} ({}), dispute {}{}", ref.caseId(), target, request.resolution(),
                finalStatus, transitioned ? " (transitioned)" : "");

        return new CloseCaseResult(ref.caseId(), target, finalStatus, transitioned,
                request.resolution(), now);
    }

    // --- progress and events ---------------------------------------------------------------------

    @Override
    public void publishCaseEvent(CaseEventCommand command) {
        Instant now = clock.now();
        if (command.status() != null) {
            caseWriter.updateStatus(command.caseId(), command.status(), command.progressPercent(), now);
        }
        if (command.eventType() == null) {
            return;
        }
        if (!command.eventType().isCaseEvent()) {
            throw new ValidationException("publishCaseEvent refuses non-CASE event type "
                    + command.eventType());
        }
        Map<String, Object> payload = new LinkedHashMap<>(command.payload());
        if (command.phase() != null) {
            payload.putIfAbsent("phase", command.phase().name());
            payload.putIfAbsent("step", command.phase().step());
            payload.putIfAbsent("progressPercent", command.phase().percent());
        }
        publish(command.eventType(), command.caseId(), command.merchantId(), payload,
                command.idempotencyKey(), command.correlationId(), command.causationId(), now);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private DisputeView requireDispute(String disputeId) {
        return disputeService.find(disputeId)
                .orElseThrow(() -> new NotFoundException("DISPUTE", disputeId));
    }

    /**
     * Best-effort dispute transition. {@code DisputeService} validates the move against its own
     * table and throws {@code ConflictException} on an illegal one; a workflow step must not fail
     * because the dispute already moved on, so an illegal transition is logged and ignored.
     */
    private DisputeStatus advanceDispute(DisputeView dispute, DisputeStatus target, String actor,
                                         String reason) {
        if (dispute == null || target == null || dispute.status() == target || dispute.isTerminal()) {
            return dispute == null ? null : dispute.status();
        }
        try {
            return disputeService.updateStatus(dispute.disputeId(), target,
                    actor == null ? SERVICE_NAME : actor, reason).status();
        } catch (RuntimeException e) {
            log.warn("dispute {} could not move {} -> {}: {}", dispute.disputeId(), dispute.status(),
                    target, e.toString());
            return dispute.status();
        }
    }

    /**
     * Publish one CASE event.
     *
     * <p>The event id is derived from the idempotency key rather than generated, so an activity
     * retry republishes a byte-identical event and every downstream consumer deduplicates it on
     * {@code eventId} without any extra coordination.</p>
     */
    private void publish(EventType eventType, String caseId, String merchantId,
                         Map<String, Object> payload, String idempotencyKey, String correlationId,
                         String causationId, Instant at) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? eventType.name() + ":" + caseId : idempotencyKey;
        CanonicalEvent event = new CanonicalEvent(
                deterministicEventId(key),
                eventType,
                CanonicalEvent.CURRENT_SCHEMA_VERSION,
                AggregateType.CASE,
                caseId,
                merchantId,
                correlationId,
                causationId,
                at,
                at,
                EventSource.INTERNAL,
                key,
                Json.tree(payload));
        publisher.publish(Topics.CASE_EVENTS, event);
    }

    /** UUIDv3 over the idempotency key: stable across retries, still a valid UUID string. */
    public static String deterministicEventId(String idempotencyKey) {
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private InvestigationContext buildContext(String investigationId, CaseRef ref, DisputeView dispute,
                                              ReadinessSnapshot snapshot, PolicyView policy) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transactionId", ref.transactionId());
        summary.put("merchantId", ref.merchantId());
        summary.put("reasonCode", dispute.reasonCode().name());
        summary.put("disputeStatus", dispute.status().name());
        // Money crosses this boundary as minor units plus currency, never as a decimal.
        summary.put("amountMinor", dispute.amount() == null ? 0L : dispute.amount().amountMinor());
        summary.put("currency", dispute.amount() == null ? null : dispute.amount().currency());
        summary.put("openedAt", String.valueOf(dispute.openedAt()));
        summary.put("deadlineAt", String.valueOf(dispute.deadlineAt()));
        summary.put("readinessScore", snapshot.score());
        summary.put("readinessBand", String.valueOf(snapshot.band()));
        summary.put("policyVersionId", snapshot.policyVersionId());

        return new InvestigationContext(
                investigationId,
                ref.caseId(),
                ref.disputeId(),
                ref.merchantId(),
                ref.transactionId(),
                dispute.reasonCode(),
                dispute.amount(),
                dispute.deadlineAt(),
                summary,
                evidenceRepository.findByTransactionId(ref.transactionId()),
                snapshot.requirements(),
                snapshot.gaps(),
                snapshot.contradictions(),
                policy.toConstraints(),
                timelineService.timeline(ref.transactionId()),
                new HistoricalContext(cases.merchantWinRate(ref.merchantId()),
                        cases.similarCaseCount(ref.merchantId(), dispute.reasonCode())));
    }

    private static InvestigationResult withInvestigationId(InvestigationResult result, String id) {
        if (id.equals(result.investigationId())) {
            return result;
        }
        return new InvestigationResult(id, result.classification(), result.confidence(),
                result.supportingEvidence(), result.missingEvidence(), result.contradictions(),
                result.reasoningSummary(), result.narrative(), result.recommendedAction(),
                result.citations(), result.modelMetadata());
    }

    private static String providerOf(InvestigationResult result) {
        return result.modelMetadata() == null ? null : result.modelMetadata().provider();
    }

    private static String modelOf(InvestigationResult result) {
        return result.modelMetadata() == null ? null : result.modelMetadata().model();
    }

    private static int promptTokens(InvestigationResult result) {
        return result.modelMetadata() == null ? 0 : result.modelMetadata().promptTokens();
    }

    private static int completionTokens(InvestigationResult result) {
        return result.modelMetadata() == null ? 0 : result.modelMetadata().completionTokens();
    }

    private static int attempt(InvestigationResult result) {
        return result.modelMetadata() == null ? 1 : result.modelMetadata().attempt();
    }

    private void countWorkflowFailure() {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(MetricNames.WORKFLOW_FAILURES_TOTAL,
                    "workflow", DisputeCaseWorkflow.WORKFLOW_TYPE).increment();
        } catch (RuntimeException e) {
            // metrics never block a close
        }
    }
}
