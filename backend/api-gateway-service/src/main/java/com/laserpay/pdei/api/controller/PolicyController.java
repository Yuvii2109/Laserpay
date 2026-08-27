package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.PolicyUpsertRequest;
import com.laserpay.pdei.api.dto.RequirementsResponse;
import com.laserpay.pdei.api.service.PolicyApiService;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.policy.PolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/policies} and {@code /api/v1/requirements} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET /policies                        ?merchantId
 * GET /policies/{policyId}
 * GET /policies/{policyId}/requirements
 * PUT /policies/{policyId}             new version (immutable history)
 * GET /requirements                    ?reasonCode=GOODS_NOT_RECEIVED
 * </pre>
 *
 * <p>PUT appends a version and closes the previous interval; it never rewrites one. That is what
 * makes a decision taken months ago replayable against the rules that were actually in force, which
 * is the entire point of versioning a policy rather than editing it.</p>
 */
@RestController
@Tag(name = "policies", description = "Versioned merchant policies and the requirement matrix")
public class PolicyController {

    private final PolicyApiService policies;

    public PolicyController(PolicyApiService policies) {
        this.policies = policies;
    }

    @GetMapping("/api/v1/policies")
    @Operation(summary = "Policies of one merchant")
    public List<PolicyView> list(@RequestParam(name = "merchantId") String merchantId) {
        return policies.list(merchantId);
    }

    @GetMapping("/api/v1/policies/{policyId}")
    @Operation(summary = "The version of a policy currently in force")
    public PolicyView get(@PathVariable("policyId") String policyId) {
        return policies.get(policyId);
    }

    @GetMapping("/api/v1/policies/{policyId}/history")
    @Operation(summary = "Every version of a policy, newest first")
    public List<PolicyView> history(@PathVariable("policyId") String policyId) {
        return policies.history(policyId);
    }

    @GetMapping("/api/v1/policies/{policyId}/requirements")
    @Operation(summary = "Requirement matrix of a policy version")
    public RequirementsResponse requirements(@PathVariable("policyId") String policyId) {
        return policies.requirements(policyId);
    }

    @PutMapping("/api/v1/policies/{policyId}")
    @Operation(summary = "Publish a new policy version",
            description = "Appends an immutable version and closes the previous interval. Unset "
                    + "fields fall back to the seeded defaults for the reason code.")
    public PolicyView publish(@PathVariable("policyId") String policyId,
                              @Valid @RequestBody PolicyUpsertRequest request) {
        return policies.publish(policyId, request);
    }

    /**
     * {@code GET /requirements?reasonCode=...}, optionally scoped to a merchant.
     *
     * <p>Without a merchant this answers from the seeded platform matrix, which is what the
     * requirement-matrix documentation screen renders. With one it answers from that merchant's
     * applicable policy, and {@code defaultPolicy} in the response says which of the two happened.</p>
     */
    @GetMapping("/api/v1/requirements")
    @Operation(summary = "Requirements for a reason code",
            description = "Merchant-scoped when merchantId is supplied, otherwise the platform "
                    + "default matrix. Omit reasonCode with a merchantId for the baseline profile.")
    public RequirementsResponse requirementsFor(
            @RequestParam(name = "reasonCode", required = false) DisputeReasonCode reasonCode,
            @RequestParam(name = "merchantId", required = false) String merchantId) {
        return policies.requirementsFor(merchantId, reasonCode);
    }
}
