package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.persistence.entity.EvidenceRequirementEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The requirement matrix scored by ReadinessEngine and served by
 * {@code GET /requirements?reasonCode=...} and {@code GET /policies/{policyId}/requirements}.
 */
@Repository
public interface EvidenceRequirementRepository extends JpaRepository<EvidenceRequirementEntity, String> {

    List<EvidenceRequirementEntity> findByReasonCode(DisputeReasonCode reasonCode);

    List<EvidenceRequirementEntity> findByPolicyIdAndPolicyVersion(String policyId, int policyVersion);

    List<EvidenceRequirementEntity> findByMerchantIdAndReasonCode(String merchantId, DisputeReasonCode reasonCode);

    List<EvidenceRequirementEntity> findByPolicyIdAndPolicyVersionAndStrengthIn(
            String policyId, int policyVersion, Collection<RequirementStrength> strengths);

    /**
     * Baseline requirement profile: the union of MANDATORY requirements across every reason code
     * this merchant has policies for. Used when readiness is computed without a reason code
     * (contract section 7).
     */
    @Query("""
            SELECT r FROM EvidenceRequirementEntity r
            WHERE r.merchantId = :merchantId AND r.strength = com.laserpay.pdei.common.domain.RequirementStrength.MANDATORY
            """)
    List<EvidenceRequirementEntity> findBaselineProfile(@Param("merchantId") String merchantId);
}
