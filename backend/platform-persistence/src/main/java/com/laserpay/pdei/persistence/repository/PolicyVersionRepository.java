package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.PolicyVersionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Immutable policy history: {@code PUT /policies/{policyId}} appends, never edits. */
@Repository
public interface PolicyVersionRepository extends JpaRepository<PolicyVersionEntity, String> {

    List<PolicyVersionEntity> findByPolicyIdOrderByVersionNumberDesc(String policyId);

    Optional<PolicyVersionEntity> findByPolicyIdAndVersionNumber(String policyId, int versionNumber);

    Optional<PolicyVersionEntity> findTopByPolicyIdOrderByVersionNumberDesc(String policyId);

    /** The version that was in force at a past instant — replays an old decision faithfully. */
    @Query("""
            SELECT v FROM PolicyVersionEntity v
            WHERE v.policyId = :policyId
              AND v.effectiveFrom <= :at
              AND (v.effectiveTo IS NULL OR v.effectiveTo > :at)
            ORDER BY v.versionNumber DESC
            """)
    List<PolicyVersionEntity> findInForceAt(@Param("policyId") String policyId, @Param("at") Instant at);
}
