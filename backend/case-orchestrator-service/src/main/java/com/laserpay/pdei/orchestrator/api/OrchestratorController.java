package com.laserpay.pdei.orchestrator.api;

import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.orchestrator.model.CaseProgress;
import com.laserpay.pdei.orchestrator.model.CaseState;
import com.laserpay.pdei.orchestrator.model.HumanDecisionType;
import com.laserpay.pdei.orchestrator.signal.CaseSignalService;
import com.laserpay.pdei.orchestrator.signal.CaseWorkflowDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal control surface for running cases, base {@code /orchestrator/v1}.
 *
 * <p>It exists so that <b>api-gateway-service needs no Temporal client</b>. The gateway's public
 * routes {@code POST /api/v1/cases/{caseId}/approve|reject|submit} become plain HTTP calls to this
 * service, which owns the only Temporal connection in the platform.</p>
 *
 * <p>Four capabilities, matching what an operator or the gateway actually needs:</p>
 * <ul>
 *   <li><b>signal</b> - the four contract signals, generically and through named routes;</li>
 *   <li><b>query</b> - {@code getCaseState} and {@code getProgress}, side-effect free;</li>
 *   <li><b>terminate</b> - the blunt stop, for when a case is stuck;</li>
 *   <li><b>describe</b> - Temporal's own view of the execution.</li>
 * </ul>
 *
 * <p>This API is internal: it is not exposed publicly and carries no authentication of its own.
 * See this module's context.md, "Known gaps".</p>
 */
@RestController
@RequestMapping("/orchestrator/v1")
public class OrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorController.class);

    private static final String SIGNAL_EVIDENCE_ARRIVED = "evidenceArrived";
    private static final String SIGNAL_HUMAN_DECISION = "humanDecision";
    private static final String SIGNAL_DISPUTE_UPDATED = "disputeUpdated";
    private static final String SIGNAL_CANCEL_CASE = "cancelCase";

    private final CaseSignalService signals;
    private final Clocks clock;

    public OrchestratorController(CaseSignalService signals, Clocks clock) {
        this.signals = signals;
        this.clock = clock;
    }

    // --- signal ---------------------------------------------------------------------------------

    /** Generic dispatcher over the four signals of PLATFORM-CONTRACT section 10. */
    @PostMapping("/cases/{caseId}/signal")
    public SignalAck signal(@PathVariable String caseId, @RequestBody SignalRequest request) {
        String name = request.signal() == null ? "" : request.signal().trim();
        boolean delivered = switch (name) {
            case SIGNAL_EVIDENCE_ARRIVED -> signals.evidenceArrived(caseId, request.evidenceId(),
                    request.evidenceType(), request.eventId());
            case SIGNAL_HUMAN_DECISION -> signals.decide(caseId, requireDecision(request),
                    request.actor(), request.notes());
            case SIGNAL_DISPUTE_UPDATED -> signals.disputeUpdated(caseId,
                    requireDisputeStatus(request), request.eventId(), request.reason(), clock.now());
            case SIGNAL_CANCEL_CASE -> signals.cancelCase(caseId, request.reason(), request.actor());
            default -> throw new ValidationException("unknown signal '" + name
                    + "'; expected one of evidenceArrived, humanDecision, disputeUpdated, cancelCase");
        };
        log.info("signal {} for case {}: delivered={}", name, caseId, delivered);
        return SignalAck.of(caseId, name, delivered, clock.now());
    }

    /** {@code POST /api/v1/cases/{caseId}/approve} on the gateway lands here. */
    @PostMapping("/cases/{caseId}/approve")
    public SignalAck approve(@PathVariable String caseId, @RequestBody DecisionRequest request) {
        return SignalAck.of(caseId, "humanDecision:APPROVE",
                signals.approve(caseId, request.actorOrSystem(), request.notes()), clock.now());
    }

    /** {@code POST /api/v1/cases/{caseId}/reject} on the gateway lands here. */
    @PostMapping("/cases/{caseId}/reject")
    public SignalAck reject(@PathVariable String caseId, @RequestBody DecisionRequest request) {
        return SignalAck.of(caseId, "humanDecision:REJECT",
                signals.reject(caseId, request.actorOrSystem(), request.notes()), clock.now());
    }

    /** {@code POST /api/v1/cases/{caseId}/submit} on the gateway lands here. */
    @PostMapping("/cases/{caseId}/submit")
    public SignalAck submit(@PathVariable String caseId, @RequestBody DecisionRequest request) {
        return SignalAck.of(caseId, "humanDecision:SUBMIT",
                signals.submit(caseId, request.actorOrSystem(), request.notes()), clock.now());
    }

    /** Send the case back for another assessment round rather than approving or rejecting it. */
    @PostMapping("/cases/{caseId}/request-more-evidence")
    public SignalAck requestMoreEvidence(@PathVariable String caseId,
                                         @RequestBody DecisionRequest request) {
        return SignalAck.of(caseId, "humanDecision:REQUEST_MORE_EVIDENCE",
                signals.requestMoreEvidence(caseId, request.actorOrSystem(), request.notes()),
                clock.now());
    }

    /** Wakes a case parked on the step 4 missing-evidence timer. */
    @PostMapping("/cases/{caseId}/evidence-arrived")
    public SignalAck evidenceArrived(@PathVariable String caseId,
                                     @RequestBody SignalRequest request) {
        return SignalAck.of(caseId, SIGNAL_EVIDENCE_ARRIVED,
                signals.evidenceArrived(caseId, request.evidenceId(), request.evidenceType(),
                        request.eventId()),
                clock.now());
    }

    /** Graceful stop: the workflow closes its case row and returns. */
    @PostMapping("/cases/{caseId}/cancel")
    public SignalAck cancel(@PathVariable String caseId, @RequestBody SignalRequest request) {
        return SignalAck.of(caseId, SIGNAL_CANCEL_CASE,
                signals.cancelCase(caseId, request.reason(), request.actor()), clock.now());
    }

    // --- query ----------------------------------------------------------------------------------

    @GetMapping("/cases/{caseId}/state")
    public CaseState state(@PathVariable String caseId) {
        return signals.getCaseState(caseId);
    }

    @GetMapping("/cases/{caseId}/progress")
    public CaseProgress progress(@PathVariable String caseId) {
        return signals.getProgress(caseId);
    }

    /** Generic query dispatcher, mirroring the two {@code @QueryMethod}s by name. */
    @GetMapping("/cases/{caseId}/query")
    public ResponseEntity<Object> query(@PathVariable String caseId,
                                        @RequestParam(name = "name") String name) {
        return switch (name) {
            case "getCaseState" -> ResponseEntity.ok(signals.getCaseState(caseId));
            case "getProgress" -> ResponseEntity.ok(signals.getProgress(caseId));
            default -> throw new ValidationException("unknown query '" + name
                    + "'; expected getCaseState or getProgress");
        };
    }

    // --- control --------------------------------------------------------------------------------

    /**
     * Terminate the execution outright. Prefer {@code /cancel}: termination skips
     * {@code closeCase}, so the case row keeps whatever status it had.
     */
    @PostMapping("/cases/{caseId}/terminate")
    public SignalAck terminate(@PathVariable String caseId, @RequestBody SignalRequest request) {
        boolean terminated = signals.terminate(caseId, request.reason(), request.actor());
        log.warn("terminate requested for case {} by {}: {}", caseId, request.actor(),
                request.reason());
        return new SignalAck(caseId, "terminate", terminated,
                terminated ? "workflow terminated; the case row was NOT closed by the workflow"
                        : "no running workflow for this case",
                clock.now());
    }

    @GetMapping("/cases/{caseId}/describe")
    public CaseWorkflowDescription describe(@PathVariable String caseId) {
        return signals.describe(caseId);
    }

    // --- validation ----------------------------------------------------------------------------

    private static HumanDecisionType requireDecision(SignalRequest request) {
        if (request.decision() == null) {
            throw new ValidationException("humanDecision requires a 'decision' of APPROVE, REJECT,"
                    + " SUBMIT or REQUEST_MORE_EVIDENCE");
        }
        return request.decision();
    }

    private static com.laserpay.pdei.common.domain.DisputeStatus requireDisputeStatus(
            SignalRequest request) {
        if (request.disputeStatus() == null) {
            throw new ValidationException("disputeUpdated requires a 'disputeStatus'");
        }
        return request.disputeStatus();
    }
}
