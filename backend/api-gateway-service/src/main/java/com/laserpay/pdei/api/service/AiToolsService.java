package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.ContradictionsResponse;
import com.laserpay.pdei.api.dto.RelatedEvidenceResponse;
import com.laserpay.pdei.api.dto.RequirementsResponse;
import com.laserpay.pdei.api.dto.TimelineResponse;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.readiness.ContradictionDetector;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import com.laserpay.pdei.core.timeline.TimelineService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only fact surface behind {@code /api/v1/ai-tools/*} (contract section 8.6).
 *
 * <p>This is the platform's answer to hallucination. The Python reasoner is not given a database
 * connection or a write path; it is given ten lookups that return exactly what Postgres holds, and
 * every claim it makes afterwards is re-checked against the same rows by {@code AiResultValidator}.
 * Nothing in this class mutates anything, and the class is marked {@code readOnly} at the
 * transaction level so an accidental write would fail rather than succeed quietly.</p>
 *
 * <h2>Why order, shipment and refund resolve through the transaction</h2>
 * <p>{@code TransactionRepositoryPort} projects a whole transaction into {@code TransactionFacts},
 * which is the same shape the contradiction detector and the timeline builder consume. Rather than
 * defining a second, subtly different projection of an order, these lookups resolve the owning
 * transaction id from the entity row and then pick the matching record out of the canonical
 * projection. One shape, one mapping, and the model sees an order exactly as the detector does.</p>
 */
@Service
@Transactional(readOnly = true)
public class AiToolsService {

    private final TransactionRepositoryPort transactions;
    private final EvidenceService evidenceService;
    private final ContradictionDetector contradictionDetector;
    private final PolicyEngine policyEngine;
    private final TimelineService timelineService;
    private final EntityOwnerResolver ownerResolver;
    private final Clocks clock;

    public AiToolsService(TransactionRepositoryPort transactions,
                          EvidenceService evidenceService,
                          ContradictionDetector contradictionDetector,
                          PolicyEngine policyEngine,
                          TimelineService timelineService,
                          EntityOwnerResolver ownerResolver,
                          Clocks clock) {
        this.transactions = transactions;
        this.evidenceService = evidenceService;
        this.contradictionDetector = contradictionDetector;
        this.policyEngine = policyEngine;
        this.timelineService = timelineService;
        this.ownerResolver = ownerResolver;
        this.clock = clock;
    }

    /** {@code GET /ai-tools/transaction/{id}}. */
    public TransactionFacts transaction(String transactionId) {
        return facts(transactionId);
    }

    /** {@code GET /ai-tools/order/{id}}. */
    public TransactionFacts.OrderFact order(String orderId) {
        String transactionId = ownerResolver.transactionIdForOrder(orderId)
                .orElseThrow(() -> new NotFoundException("ORDER", orderId));
        return facts(transactionId).orders().stream()
                .filter(order -> orderId.equals(order.orderId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("ORDER", orderId));
    }

    /** {@code GET /ai-tools/shipment/{id}}. */
    public TransactionFacts.ShipmentFact shipment(String shipmentId) {
        String transactionId = ownerResolver.transactionIdForShipment(shipmentId)
                .orElseThrow(() -> new NotFoundException("SHIPMENT", shipmentId));
        return facts(transactionId).shipments().stream()
                .filter(shipment -> shipmentId.equals(shipment.shipmentId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("SHIPMENT", shipmentId));
    }

    /** {@code GET /ai-tools/refund/{id}}. */
    public TransactionFacts.RefundFact refund(String refundId) {
        String transactionId = ownerResolver.transactionIdForRefund(refundId)
                .orElseThrow(() -> new NotFoundException("REFUND", refundId));
        return facts(transactionId).refunds().stream()
                .filter(refund -> refundId.equals(refund.refundId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("REFUND", refundId));
    }

    /** {@code GET /ai-tools/evidence/{id}}. */
    public EvidenceView evidence(String evidenceId) {
        return evidenceService.require(evidenceId);
    }

    /** {@code GET /ai-tools/evidence/related?transactionId=}. */
    public RelatedEvidenceResponse relatedEvidence(String transactionId) {
        requireText(transactionId, "transactionId");
        List<EvidenceView> evidence = evidenceService.findForTransaction(transactionId);
        return RelatedEvidenceResponse.of(transactionId, evidence, clock.now());
    }

    /** {@code GET /ai-tools/contradictions?transactionId=}. */
    public ContradictionsResponse contradictions(String transactionId) {
        requireText(transactionId, "transactionId");
        TransactionFacts facts = facts(transactionId);
        List<EvidenceView> evidence = evidenceService.findForTransaction(transactionId);
        return ContradictionsResponse.of(transactionId,
                contradictionDetector.detect(facts, evidence, clock.now()), clock.now());
    }

    /** {@code GET /ai-tools/policy/applicable?merchantId=&reasonCode=}. */
    public PolicyView applicablePolicy(String merchantId, DisputeReasonCode reasonCode) {
        requireText(merchantId, "merchantId");
        return policyEngine.applicablePolicy(merchantId, reasonCode);
    }

    /** {@code GET /ai-tools/requirements?reasonCode=}. */
    public RequirementsResponse requirements(DisputeReasonCode reasonCode, String merchantId) {
        if (reasonCode == null) {
            throw ValidationException.field("reasonCode", "is required");
        }
        if (merchantId == null || merchantId.isBlank()) {
            return RequirementsResponse.of(null, reasonCode, null, null, true,
                    policyEngine.requirements(reasonCode));
        }
        PolicyView policy = policyEngine.applicablePolicy(merchantId, reasonCode);
        return RequirementsResponse.of(merchantId, reasonCode, policy.policyId(),
                policy.policyVersionId(), policy.defaultPolicy(),
                policyEngine.requirements(merchantId, reasonCode));
    }

    /** {@code GET /ai-tools/timeline/{transactionId}}. */
    public TimelineResponse timeline(String transactionId) {
        requireText(transactionId, "transactionId");
        requireTransaction(transactionId);
        return TimelineResponse.of(transactionId, timelineService.timeline(transactionId), clock.now());
    }

    // ---------------------------------------------------------------------------------------

    private TransactionFacts facts(String transactionId) {
        requireText(transactionId, "transactionId");
        return transactions.findFacts(transactionId)
                .orElseThrow(() -> new NotFoundException("TRANSACTION", transactionId));
    }

    private void requireTransaction(String transactionId) {
        if (!transactions.exists(transactionId)) {
            throw new NotFoundException("TRANSACTION", transactionId);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, "is required");
        }
    }

    /**
     * Resolves an order, shipment or refund id to the transaction that owns it.
     *
     * <p>Broken out as its own bean so {@link AiToolsService} depends on one narrow port instead of
     * three JPA repositories, which keeps it unit-testable without a persistence context.</p>
     */
    public interface EntityOwnerResolver {

        Optional<String> transactionIdForOrder(String orderId);

        Optional<String> transactionIdForShipment(String shipmentId);

        Optional<String> transactionIdForRefund(String refundId);
    }
}
