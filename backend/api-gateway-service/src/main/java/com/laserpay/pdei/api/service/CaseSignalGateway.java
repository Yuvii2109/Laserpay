package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.CaseDecisionRequest;
import com.laserpay.pdei.api.dto.CaseDecisionResponse;
import com.laserpay.pdei.api.support.CorrelationIds;
import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.error.ConflictException;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers a human case decision to the Temporal workflow, with a deterministic local fallback.
 *
 * <h2>Primary path</h2>
 * <p>POST to case-orchestrator-service, which signals {@code humanDecision} on
 * {@code DisputeCaseWorkflow} (contract section 10). That is the correct path: the workflow owns the
 * case lifecycle, and a decision it never hears about leaves it blocked on
 * {@code awaitHumanApproval} until the timer escalates.</p>
 *
 * <h2>Fallback</h2>
 * <p>case-orchestrator-service has no REST surface in PLATFORM-CONTRACT.md section 8, so the signal
 * endpoint used below is this module's assumption about a service that does not exist yet. When it
 * is unreachable, disabled, or answers with an error, the decision still lands:
 * {@link CaseTransitionWriter} updates the case row (including the approval columns the schema
 * already carries), publishes the corresponding CASE event on {@code pdei.case.events.v1}, and
 * writes the decision to the hash-chained audit log.</p>
 *
 * <p>The fallback is not silent. {@link CaseDecisionResponse#deliveredTo()} says which path ran, so
 * the frontend can show that a case was advanced locally and its workflow has not been told. Losing
 * a human decision entirely would be far worse than recording one the workflow has yet to see, and
 * the audit entry means the decision is never unaccounted for.</p>
 *
 * <h2>Transaction boundary</h2>
 * <p>This class is deliberately <strong>not</strong> transactional. The HTTP call happens first,
 * outside any transaction, and only then does {@link CaseTransitionWriter#apply} open one for the
 * four local writes. Holding a database connection open across a remote call with a multi-second
 * timeout is how one slow dependency turns into pool exhaustion.</p>
 */
@Component
public class CaseSignalGateway {

    private static final Logger log = LoggerFactory.getLogger(CaseSignalGateway.class);

    private static final String ENTITY_TYPE = "CASE";

    private final ApiProperties properties;
    private final CaseRepositoryPort cases;
    private final CaseTransitionWriter transitionWriter;
    private final Clocks clock;
    private final RestClient restClient;

    public CaseSignalGateway(ApiProperties properties,
                             CaseRepositoryPort cases,
                             CaseTransitionWriter transitionWriter,
                             Clocks clock) {
        this.properties = properties;
        this.cases = cases;
        this.transitionWriter = transitionWriter;
        this.clock = clock;
        this.restClient = buildRestClient(properties.getOrchestrator());
    }

    /**
     * Apply one human decision.
     *
     * @throws NotFoundException when the case does not exist
     * @throws ConflictException when the decision is illegal from the case's current status
     */
    public CaseDecisionResponse decide(String caseId, CaseDecision decision, CaseDecisionRequest request) {
        CaseView view = cases.findCase(caseId)
                .orElseThrow(() -> new NotFoundException(ENTITY_TYPE, caseId));
        CaseStatus previous = view.status();
        if (!decision.isLegalFrom(previous)) {
            throw ConflictException.illegalTransition(caseId, previous, decision.target());
        }

        String correlationId = CorrelationIds.current();
        boolean signalled = trySignal(caseId, view, decision, request, correlationId);
        transitionWriter.apply(view, decision, request, correlationId, signalled);

        return new CaseDecisionResponse(
                caseId,
                decision.name(),
                CaseDecision.SIGNAL_NAME,
                previous,
                decision.target(),
                signalled ? CaseDecisionResponse.TEMPORAL_SIGNAL : CaseDecisionResponse.LOCAL_TRANSITION,
                request.actor(),
                request.note(),
                clock.now());
    }

    // ---------------------------------------------------------------------------------------

    /** @return true when case-orchestrator-service accepted the signal */
    private boolean trySignal(String caseId, CaseView view, CaseDecision decision,
                              CaseDecisionRequest request, String correlationId) {
        if (!properties.getOrchestrator().isEnabled()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", caseId);
        body.put("workflowId", view.workflowId() == null ? "case-" + caseId : view.workflowId());
        body.put("decision", decision.name());
        body.put("actor", request.actor());
        body.put("note", request.note());
        body.put("correlationId", correlationId);
        body.put("decidedAt", clock.now().toString());
        try {
            restClient.post()
                    .uri("/orchestrator/v1/cases/{caseId}/signals/{signal}", caseId, CaseDecision.SIGNAL_NAME)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CorrelationIds.HEADER, correlationId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not signal {} for case {} ({}); applying the local transition instead",
                    CaseDecision.SIGNAL_NAME, caseId, e.toString());
            return false;
        }
    }

    private static RestClient buildRestClient(ApiProperties.Orchestrator config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(clamp(config.getConnectTimeout(), Duration.ofSeconds(2)));
        factory.setReadTimeout(clamp(config.getReadTimeout(), Duration.ofSeconds(10)));
        return RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Both timeouts are short and always bounded. A human clicking Approve must not wait on a
     * workflow service that is hanging: the local transition is a perfectly good answer, and an
     * unbounded timeout here would turn one slow dependency into a slow API.
     */
    private static Duration clamp(Duration duration, Duration fallback) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return fallback;
        }
        return duration.compareTo(Duration.ofMinutes(1)) > 0 ? Duration.ofMinutes(1) : duration;
    }
}
