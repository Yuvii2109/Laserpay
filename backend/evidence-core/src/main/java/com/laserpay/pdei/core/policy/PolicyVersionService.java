package com.laserpay.pdei.core.policy;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.spi.PolicyRepositoryPort;
import com.laserpay.pdei.core.util.CoreErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable versioning of policies.
 *
 * <p>Publishing never rewrites a stored version. It appends a new one, closes the open interval of
 * the previous one and audits the transition, so any past decision can be replayed against the exact
 * policy that was in force at the time. That property is what makes the platform defensible: a
 * reviewer six months later can prove which rules the machine was following.</p>
 */
public class PolicyVersionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyVersionService.class);
    private static final String ENTITY_TYPE = "POLICY";

    private final PolicyRepositoryPort policies;
    private final AuditRecorder audit;
    private final Clocks clock;

    public PolicyVersionService(PolicyRepositoryPort policies, AuditRecorder audit, Clocks clock) {
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Publish a new version of {@code policyId}, or create the policy when {@code policyId} is null.
     *
     * @return the newly published, now-active version
     */
    public PolicyView publish(String policyId, PolicyDraft draft, String actor) {
        CoreErrors.requireValue(draft, "draft");
        CoreErrors.requireText(draft.merchantId(), "draft.merchantId");
        Instant now = clock.now();

        Optional<PolicyView> current = policyId == null ? Optional.empty() : findById(policyId);
        String resolvedPolicyId = policyId == null ? Ids.policy() : policyId;
        int nextVersion = current.map(PolicyView::version).orElse(0) + 1;
        String checksum = checksum(draft);

        current.ifPresent(existing -> {
            if (checksum.equals(existing.checksum())) {
                log.info("policy {} unchanged (checksum {}), publishing a new version anyway on explicit request",
                        resolvedPolicyId, checksum);
            }
        });

        PolicyView published = new PolicyView(
                resolvedPolicyId,
                resolvedPolicyId + "-V" + nextVersion,
                nextVersion,
                draft.merchantId(),
                draft.reasonCode(),
                draft.requirements(),
                draft.permittedActions(),
                draft.prohibitedEvidenceTypes(),
                draft.autoPrepareMinConfidence(),
                draft.maxContradictions(),
                draft.minReadinessScoreForAutoPrepare(),
                draft.humanReviewAboveAmountMinor(),
                draft.currency(),
                draft.autoSubmitEnabled(),
                draft.responseWindowDays(),
                draft.expiringSoonDays(),
                actor,
                checksum,
                now,
                null,
                false);

        current.ifPresent(existing ->
                policies.closePreviousVersion(resolvedPolicyId, existing.policyVersionId(), now));
        policies.insertVersion(published);

        audit.record(AuditCommand.of(ENTITY_TYPE, resolvedPolicyId, draft.merchantId(),
                        "POLICY_VERSION_PUBLISHED", actor, ActorType.MERCHANT_USER)
                .withBefore(current.orElse(null))
                .withAfter(published)
                .withCorrelationId(null));

        log.info("published policy {} version {} for merchantId={} reasonCode={}",
                resolvedPolicyId, nextVersion, draft.merchantId(), draft.reasonCode());
        return published;
    }

    /** Full immutable history for a policy, newest first. */
    public List<PolicyView> history(String policyId) {
        return policies.findHistory(CoreErrors.requireText(policyId, "policyId"));
    }

    public Optional<PolicyView> findById(String policyId) {
        return policies.findByPolicyId(policyId);
    }

    public Optional<PolicyView> findVersion(String policyVersionId) {
        return policies.findByVersionId(policyVersionId);
    }

    public List<PolicyView> findByMerchant(String merchantId) {
        return policies.findByMerchant(CoreErrors.requireText(merchantId, "merchantId"));
    }

    /** The version that was in force at a point in time - the replay primitive. */
    public Optional<PolicyView> activeAt(String merchantId, DisputeReasonCode reasonCode, Instant at) {
        return policies.findActive(merchantId, reasonCode, at == null ? clock.now() : at);
    }

    /**
     * Stable content hash of a draft. Two drafts with the same rules produce the same checksum, so a
     * no-op republish is visible in the audit trail.
     */
    public String checksum(PolicyDraft draft) {
        return Hashes.canonicalJsonSha256(draft, Json.mapper());
    }
}
