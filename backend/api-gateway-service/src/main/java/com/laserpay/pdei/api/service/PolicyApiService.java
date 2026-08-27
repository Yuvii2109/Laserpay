package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.PolicyUpsertRequest;
import com.laserpay.pdei.api.dto.RequirementsResponse;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyVersionService;
import com.laserpay.pdei.core.policy.PolicyView;
import com.laserpay.pdei.core.policy.RequirementSpec;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /policies} and {@code /requirements} routes.
 *
 * <p>Policies are immutable versions, never edits. {@code PUT /policies/{policyId}} appends a new
 * version and closes the previous interval, so any past decision can be replayed against the rules
 * that were actually in force when it was made.</p>
 *
 * <p>Requirement lookups always answer, even for a merchant who has never published a policy: the
 * engine falls back to the seeded platform matrix. That fallback is deterministic and is flagged in
 * the response as {@code defaultPolicy}, so nobody mistakes a platform assumption for a merchant's
 * own published rule.</p>
 */
@Service
public class PolicyApiService {

    private static final String ENTITY_TYPE = "POLICY";

    private final PolicyEngine policyEngine;
    private final PolicyVersionService versionService;

    public PolicyApiService(PolicyEngine policyEngine, PolicyVersionService versionService) {
        this.policyEngine = policyEngine;
        this.versionService = versionService;
    }

    /** {@code GET /policies?merchantId}. */
    @Transactional(readOnly = true)
    public List<PolicyView> list(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw ValidationException.field("merchantId", "is required");
        }
        return versionService.findByMerchant(merchantId);
    }

    /** {@code GET /policies/{policyId}}: the version currently in force for that policy. */
    @Transactional(readOnly = true)
    public PolicyView get(String policyId) {
        return versionService.findById(policyId)
                .orElseThrow(() -> new NotFoundException(ENTITY_TYPE, policyId));
    }

    /** Every version of one policy, newest first: the immutable history the UI renders. */
    @Transactional(readOnly = true)
    public List<PolicyView> history(String policyId) {
        List<PolicyView> history = versionService.history(policyId);
        if (history.isEmpty()) {
            throw new NotFoundException(ENTITY_TYPE, policyId);
        }
        return history;
    }

    /** {@code GET /policies/{policyId}/requirements}. */
    @Transactional(readOnly = true)
    public RequirementsResponse requirements(String policyId) {
        PolicyView policy = get(policyId);
        return RequirementsResponse.of(policy.merchantId(), policy.reasonCode(), policy.policyId(),
                policy.policyVersionId(), policy.defaultPolicy(), policy.requirements());
    }

    /**
     * {@code GET /requirements?reasonCode=...}.
     *
     * <p>With a merchant the answer is that merchant's applicable policy; without one it is the
     * seeded platform matrix, which is what the requirement-matrix documentation screen shows.</p>
     */
    @Transactional(readOnly = true)
    public RequirementsResponse requirementsFor(String merchantId, DisputeReasonCode reasonCode) {
        if (merchantId == null || merchantId.isBlank()) {
            if (reasonCode == null) {
                throw ValidationException.field("reasonCode",
                        "is required when no merchantId is supplied");
            }
            List<RequirementSpec> specs = policyEngine.requirements(reasonCode);
            return RequirementsResponse.of(null, reasonCode, null, null, true, specs);
        }
        PolicyView policy = policyEngine.applicablePolicy(merchantId, reasonCode);
        List<RequirementSpec> specs = reasonCode == null
                ? policyEngine.baselineRequirements(merchantId)
                : policyEngine.requirements(merchantId, reasonCode);
        return RequirementsResponse.of(merchantId, reasonCode, policy.policyId(),
                policy.policyVersionId(), policy.defaultPolicy(), specs);
    }

    /**
     * {@code PUT /policies/{policyId}}: publish a new version.
     *
     * <p>The published version is checksummed by the core service, so republishing an identical draft
     * is still visible in the audit trail as a distinct, no-op version rather than disappearing.</p>
     */
    @Transactional
    public PolicyView publish(String policyId, PolicyUpsertRequest request) {
        if (policyId == null || policyId.isBlank()) {
            throw ValidationException.field("policyId", "is required");
        }
        versionService.findById(policyId).ifPresent(existing -> {
            if (existing.merchantId() != null && !existing.merchantId().equals(request.merchantId())) {
                // Reassigning a policy to another merchant would silently rewrite whose rules applied
                // to every decision already made under it.
                throw new ValidationException(
                        "policy " + policyId + " belongs to a different merchant",
                        Map.of("policyId", policyId,
                                "existingMerchantId", existing.merchantId(),
                                "requestedMerchantId", String.valueOf(request.merchantId())));
            }
        });
        return versionService.publish(policyId, request.toDraft(), request.actor());
    }
}
