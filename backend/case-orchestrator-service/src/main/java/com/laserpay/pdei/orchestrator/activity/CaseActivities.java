package com.laserpay.pdei.orchestrator.activity;

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
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Everything the workflow is allowed to do to the outside world.
 *
 * <p>Workflow code is deterministic and therefore does no I/O: every database write, MinIO object,
 * AI call, Kafka publication and audit record in the dispute lifecycle happens here. The
 * implementation is a thin sequencing layer over {@code evidence-core} - the orchestrator owns
 * <em>when</em> things happen, never <em>what</em> the financial domain decides.</p>
 *
 * <p><b>Every method must be idempotent and safe to retry.</b> Temporal retries an activity up to
 * ten times (contract section 10) and can also re-run one after a worker crash, so "ran twice" is
 * the normal case, not the pathological one. Where an operation is not naturally idempotent -
 * assembling a new package version, spending an AI budget token, minting an investigation id - the
 * implementation memoises the first result under a workflow-supplied idempotency token.</p>
 *
 * <p>{@code PolicyViolationException} and {@code ValidationException} are registered as
 * non-retryable: they mean the request is wrong, and retrying a wrong request ten times only
 * delays the escalation.</p>
 */
@ActivityInterface
public interface CaseActivities {

    /** Step 1. Create (or adopt) the {@code dispute_cases} row and emit {@code CaseOpened}. */
    @ActivityMethod
    OpenCaseResult openCase(OpenCaseRequest request);

    /** Step 2. Recompute readiness and pin the current evidence set to the case. */
    @ActivityMethod
    EvidenceReport gatherEvidence(CaseRef ref);

    /** Step 3. Deterministic gap and contradiction detection for the case's transaction. */
    @ActivityMethod
    GapReport detectGaps(CaseRef ref);

    /**
     * Step 5. Contract section 9.4 admission control: does this case earn a model call?
     *
     * <p>Takes an idempotency token because admitting a case spends a Redis budget token and
     * appends a row to {@code ai_admission_log}. A retry must reuse the first decision rather than
     * spend the budget again.</p>
     */
    @ActivityMethod
    AdmissionOutcome runAdmissionControl(CaseRef ref, String idempotencyToken);

    /** Step 6. Investigate through the AI service, or deterministically when AI was bypassed. */
    @ActivityMethod
    InvestigationOutcome investigate(InvestigationRequest request);

    /** Step 7. Contract section 9.3 validation plus the policy gate. */
    @ActivityMethod
    GateOutcome validateAndGate(GateRequest request);

    /** Step 9. Assemble the MinIO bundle and manifest for the representment. */
    @ActivityMethod
    PackageResult prepareRepresentmentPackage(PreparePackageRequest request);

    /** Step 10. Hand the package to the network submitter and record the receipt. */
    @ActivityMethod
    SubmissionReceipt submitRepresentment(SubmitRequest request);

    /** Step 12. Close the case, transition the dispute where legal, emit {@code CaseClosed}. */
    @ActivityMethod
    CloseCaseResult closeCase(CloseCaseRequest request);

    /** Persist case status/progress and optionally publish a CASE event. Used by every phase. */
    @ActivityMethod
    void publishCaseEvent(CaseEventCommand command);
}
