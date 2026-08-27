package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.core.policy.PolicyView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read/write port for {@code pdei.policies}, {@code policy_versions}, {@code evidence_requirements}. */
public interface PolicyRepositoryPort {

    /** The version in force for this merchant/reason code at {@code at}, if any. */
    Optional<PolicyView> findActive(String merchantId, DisputeReasonCode reasonCode, Instant at);

    Optional<PolicyView> findByPolicyId(String policyId);

    Optional<PolicyView> findByVersionId(String policyVersionId);

    /** Full immutable history, newest version first. */
    List<PolicyView> findHistory(String policyId);

    List<PolicyView> findByMerchant(String merchantId);

    /** Append a new immutable version (policy row upserted, version row inserted, requirements inserted). */
    void insertVersion(PolicyView version);

    /** Close the open interval of the previous version. Never rewrites its content. */
    void closePreviousVersion(String policyId, String previousVersionId, Instant effectiveTo);

    /**
     * The merchant's most frequent dispute reason codes, most frequent first. Drives the baseline
     * requirement profile used when readiness is computed without a reason code (contract 7).
     */
    List<DisputeReasonCode> topReasonCodes(String merchantId, int limit);
}
