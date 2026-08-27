package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.persistence.entity.PolicyEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Policy heads. {@link #findApplicable} implements PolicyEngine.applicablePolicy(...). */
@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, String> {

    List<PolicyEntity> findByMerchantIdAndActiveTrue(String merchantId);

    List<PolicyEntity> findByMerchantId(String merchantId);

    Optional<PolicyEntity> findByMerchantIdAndReasonCodeAndActiveTrue(String merchantId, DisputeReasonCode reasonCode);

    /**
     * Most specific policy in force at {@code at}: a REASON_CODE policy beats a MERCHANT policy,
     * which beats a GLOBAL one; ties break on the newest effective_from.
     */
    @Query("""
            SELECT p FROM PolicyEntity p
            WHERE p.active = TRUE
              AND (p.merchantId = :merchantId OR p.scope = 'GLOBAL')
              AND (p.reasonCode IS NULL OR p.reasonCode = :reasonCode)
              AND p.effectiveFrom <= :at
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
            ORDER BY CASE WHEN p.reasonCode = :reasonCode THEN 0
                          WHEN p.scope = 'MERCHANT'       THEN 1
                          ELSE 2 END,
                     p.effectiveFrom DESC
            """)
    List<PolicyEntity> findApplicable(@Param("merchantId") String merchantId,
                                      @Param("reasonCode") DisputeReasonCode reasonCode,
                                      @Param("at") Instant at);
}
