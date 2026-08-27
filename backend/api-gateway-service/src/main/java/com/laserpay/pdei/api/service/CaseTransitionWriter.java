package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.CaseDecisionRequest;
import com.laserpay.pdei.api.dto.CaseDecisionResponse;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.persistence.entity.DisputeCaseEntity;
import com.laserpay.pdei.persistence.repository.DisputeCaseRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a human case decision to the database, publishes the CASE event and audits it, in one
 * transaction.
 *
 * <p>Separate from {@link CaseSignalGateway} for one specific reason: the gateway calls
 * case-orchestrator-service over HTTP, and that call must not happen inside this transaction. A
 * remote call with a multi-second timeout inside an open transaction pins a database connection for
 * the duration, so one slow dependency becomes pool exhaustion. Splitting the two puts the HTTP call
 * outside and keeps the four writes atomic with respect to each other.</p>
 *
 * <p>The writes are idempotent. When the workflow also processes the signal it will eventually set
 * the same status, and setting a status twice changes nothing.</p>
 */
@Component
public class CaseTransitionWriter {

    private static final Logger log = LoggerFactory.getLogger(CaseTransitionWriter.class);

    private static final String ENTITY_TYPE = "CASE";

    private final CaseRepositoryPort cases;
    private final DisputeCaseRepository caseRows;
    private final EventPublisherPort publisher;
    private final ObjectProvider<AuditRecorder> auditRecorders;
    private final Clocks clock;

    public CaseTransitionWriter(CaseRepositoryPort cases,
                                DisputeCaseRepository caseRows,
                                EventPublisherPort publisher,
                                ObjectProvider<AuditRecorder> auditRecorders,
                                Clocks clock) {
        this.cases = cases;
        this.caseRows = caseRows;
        this.publisher = publisher;
        this.auditRecorders = auditRecorders;
        this.clock = clock;
    }

    /**
     * Apply the decision locally.
     *
     * @param signalled whether case-orchestrator-service already accepted the signal; recorded in the
     *                  event payload and the audit entry so the two paths stay distinguishable
     */
    @Transactional
    public void apply(CaseView view, CaseDecision decision, CaseDecisionRequest request,
                      String correlationId, boolean signalled) {
        Instant now = clock.now();
        String caseId = view.caseId();

        cases.updateCaseStatus(caseId, decision.target(), now);
        recordApprovalColumns(caseId, decision, request, now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("disputeId", view.disputeId());
        payload.put("transactionId", view.transactionId());
        payload.put("decision", decision.name());
        payload.put("previousStatus", view.status() == null ? null : view.status().name());
        payload.put("status", decision.target().name());
        payload.put("actor", request.actor());
        payload.put("note", request.note());
        payload.put("deliveredTo", signalled
                ? CaseDecisionResponse.TEMPORAL_SIGNAL : CaseDecisionResponse.LOCAL_TRANSITION);

        publish(view, decision, correlationId, now, payload);
        audit(view, decision, request, correlationId, payload);
    }

    /**
     * Publication failures are logged, never thrown: the decision is already durable, and rolling a
     * correct write back because a broker hiccuped would lose a human's decision.
     */
    private void publish(CaseView view, CaseDecision decision, String correlationId,
                         Instant now, Map<String, Object> payload) {
        try {
            publisher.publish(Topics.CASE_EVENTS, CanonicalEvent.builder()
                    .eventId(Ids.eventId())
                    .eventType(decision.eventType())
                    .aggregateType(AggregateType.CASE)
                    .aggregateId(view.caseId())
                    .merchantId(view.merchantId())
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .observedAt(now)
                    .source(EventSource.MERCHANT_PORTAL)
                    .idempotencyKey("case-decision:" + view.caseId() + ":" + decision.name()
                            + ":" + now.toEpochMilli())
                    .payloadFrom(payload)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Case decision {} on {} was applied but not published: {}",
                    decision, view.caseId(), e.toString());
        }
    }

    private void audit(CaseView view, CaseDecision decision, CaseDecisionRequest request,
                       String correlationId, Map<String, Object> payload) {
        AuditRecorder recorder = auditRecorders.getIfAvailable();
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(AuditCommand.of(ENTITY_TYPE, view.caseId(), view.merchantId(),
                            "CASE_" + decision.name(), request.actor(), ActorType.MERCHANT_USER)
                    .withBefore(Map.of("status", String.valueOf(view.status())))
                    .withAfter(payload)
                    .withCorrelationId(correlationId));
        } catch (RuntimeException e) {
            log.error("Case decision {} on {} could not be audited", decision, view.caseId(), e);
        }
    }

    /**
     * Fill the approval columns the {@code dispute_cases} table already carries.
     *
     * <p>{@code CaseRepositoryPort} exposes no method for these and adding one would mean editing
     * evidence-core, which is not this module's to change. Writing them through the JPA entity keeps
     * the human decision on the row where the Case X-Ray and the audit export both look for it.</p>
     */
    private void recordApprovalColumns(String caseId, CaseDecision decision,
                                       CaseDecisionRequest request, Instant now) {
        Optional<DisputeCaseEntity> row = caseRows.findById(caseId);
        if (row.isEmpty()) {
            return;
        }
        DisputeCaseEntity entity = row.get();
        entity.setStatus(decision.target());
        entity.setUpdatedAt(now);
        switch (decision) {
            case APPROVE -> {
                entity.setApprovalActor(request.actor());
                entity.setApprovalAt(now);
                entity.setApprovalNotes(request.noteOrDefault("approved"));
                entity.setPreparedAt(now);
            }
            case REJECT -> {
                entity.setApprovalActor(request.actor());
                entity.setApprovalAt(now);
                entity.setApprovalNotes(request.noteOrDefault("rejected"));
            }
            case SUBMIT -> entity.setSubmittedAt(now);
        }
        caseRows.save(entity);
    }
}
