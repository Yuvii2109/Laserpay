package com.laserpay.pdei.api.controller.aitools;

import com.laserpay.pdei.api.dto.ContradictionsResponse;
import com.laserpay.pdei.api.dto.RelatedEvidenceResponse;
import com.laserpay.pdei.api.dto.RequirementsResponse;
import com.laserpay.pdei.api.dto.TimelineResponse;
import com.laserpay.pdei.api.service.AiToolsService;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.TransactionFacts;
import com.laserpay.pdei.core.policy.PolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ten read-only tool endpoints the Python ai-reasoning-service calls back into
 * (PLATFORM-CONTRACT.md section 8.6).
 *
 * <pre>
 * GET /api/v1/ai-tools/transaction/{id}
 * GET /api/v1/ai-tools/order/{id}
 * GET /api/v1/ai-tools/shipment/{id}
 * GET /api/v1/ai-tools/refund/{id}
 * GET /api/v1/ai-tools/evidence/{id}
 * GET /api/v1/ai-tools/evidence/related?transactionId=
 * GET /api/v1/ai-tools/contradictions?transactionId=
 * GET /api/v1/ai-tools/policy/applicable?merchantId=&amp;reasonCode=
 * GET /api/v1/ai-tools/requirements?reasonCode=
 * GET /api/v1/ai-tools/timeline/{transactionId}
 * </pre>
 *
 * <h2>Read-only by construction</h2>
 * <p>This class contains only {@code @GetMapping}. Not "no mutating logic" but no mutating
 * <em>mapping</em>: there is no POST, PUT, PATCH or DELETE handler here at all, so there is no URL
 * under {@code /ai-tools} that could change anything, whatever a caller sends. That is rule 2 of
 * contract section 17 (the LLM never mutates financial state) enforced by the shape of the code
 * rather than by a reviewer remembering it, and {@code AiToolsControllerTest} asserts it by
 * reflection so it stays true.</p>
 *
 * <p>The model is not trusted with this data either. Everything it reads here it must cite, and
 * every citation is re-checked against the same rows by {@code AiResultValidator} before it can
 * influence a case. These endpoints let the model be right; they do not let it be believed.</p>
 *
 * <h2>Authentication</h2>
 * <p>{@code ServiceTokenFilter} guards the whole prefix and requires {@code X-PDEI-Service-Token} to
 * equal {@code PDEI_SERVICE_TOKEN}. A missing or wrong token is 401 before any handler runs.</p>
 *
 * <p>Route ordering note: {@code /evidence/related} is declared before {@code /evidence/{id}} and
 * Spring prefers the literal path over the template, so the two never collide.</p>
 */
@RestController
@RequestMapping("/api/v1/ai-tools")
@Tag(name = "ai-tools", description = "Read-only fact lookups for ai-reasoning-service")
@SecurityRequirement(name = "ServiceToken")
public class AiToolsController {

    private final AiToolsService tools;

    public AiToolsController(AiToolsService tools) {
        this.tools = tools;
    }

    @GetMapping("/transaction/{transactionId}")
    @Operation(summary = "Transaction facts",
            description = "The flattened projection: payments, orders, order lines, shipments, "
                    + "deliveries, refunds and communications. Money is always minor units.")
    public TransactionFacts transaction(@PathVariable("transactionId") String transactionId) {
        return tools.transaction(transactionId);
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "One order, as the contradiction detector sees it")
    public TransactionFacts.OrderFact order(@PathVariable("orderId") String orderId) {
        return tools.order(orderId);
    }

    @GetMapping("/shipment/{shipmentId}")
    @Operation(summary = "One shipment, as the contradiction detector sees it")
    public TransactionFacts.ShipmentFact shipment(@PathVariable("shipmentId") String shipmentId) {
        return tools.shipment(shipmentId);
    }

    @GetMapping("/refund/{refundId}")
    @Operation(summary = "One refund, as the contradiction detector sees it")
    public TransactionFacts.RefundFact refund(@PathVariable("refundId") String refundId) {
        return tools.refund(refundId);
    }

    @GetMapping("/evidence/related")
    @Operation(summary = "Every artifact linked to a transaction",
            description = "Includes superseded and invalidated artifacts. usableCount separates what "
                    + "may be cited from what merely exists: an invalidated delivery proof is a "
                    + "materially different fact from no delivery proof.")
    public RelatedEvidenceResponse relatedEvidence(
            @RequestParam(name = "transactionId") String transactionId) {
        return tools.relatedEvidence(transactionId);
    }

    @GetMapping("/evidence/{evidenceId}")
    @Operation(summary = "One evidence artifact with its hash and provenance")
    public EvidenceView evidence(@PathVariable("evidenceId") String evidenceId) {
        return tools.evidence(evidenceId);
    }

    @GetMapping("/contradictions")
    @Operation(summary = "Cross-record conflicts on a transaction",
            description = "Computed fresh on every call, never read from a cached snapshot: the "
                    + "model is asking what conflicts right now.")
    public ContradictionsResponse contradictions(
            @RequestParam(name = "transactionId") String transactionId) {
        return tools.contradictions(transactionId);
    }

    @GetMapping("/policy/applicable")
    @Operation(summary = "The policy version in force for a merchant and reason code",
            description = "Falls back deterministically to the seeded platform policy, flagged as "
                    + "defaultPolicy, when the merchant has published none.")
    public PolicyView applicablePolicy(
            @RequestParam(name = "merchantId") String merchantId,
            @RequestParam(name = "reasonCode", required = false) DisputeReasonCode reasonCode) {
        return tools.applicablePolicy(merchantId, reasonCode);
    }

    @GetMapping("/requirements")
    @Operation(summary = "Evidence requirements for a reason code")
    public RequirementsResponse requirements(
            @RequestParam(name = "reasonCode") DisputeReasonCode reasonCode,
            @RequestParam(name = "merchantId", required = false) String merchantId) {
        return tools.requirements(reasonCode, merchantId);
    }

    @GetMapping("/timeline/{transactionId}")
    @Operation(summary = "Unified timeline of a transaction")
    public TimelineResponse timeline(@PathVariable("transactionId") String transactionId) {
        return tools.timeline(transactionId);
    }
}
