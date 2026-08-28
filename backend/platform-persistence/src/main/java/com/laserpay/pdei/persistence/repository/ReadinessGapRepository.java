package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.persistence.entity.ReadinessGapEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unresolved gaps are the at-risk feed ({@code GET /gaps?merchantId&type&severity}) - the
 * pre-dispute surface of the whole product.
 */
@Repository
public interface ReadinessGapRepository extends JpaRepository<ReadinessGapEntity, String> {

    Page<ReadinessGapEntity> findByMerchantIdAndSeverityAndResolvedFalse(
            String merchantId, GapSeverity severity, Pageable pageable);

    Page<ReadinessGapEntity> findByMerchantIdAndResolvedFalse(String merchantId, Pageable pageable);

    Page<ReadinessGapEntity> findByMerchantIdAndTypeAndResolvedFalse(
            String merchantId, GapType type, Pageable pageable);

    List<ReadinessGapEntity> findByTransactionIdAndResolvedFalse(String transactionId);

    List<ReadinessGapEntity> findBySnapshotId(String snapshotId);

    long countByMerchantIdAndSeverityAndResolvedFalse(String merchantId, GapSeverity severity);

    /** Closes gaps that a newly arrived evidence item has just satisfied. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ReadinessGapEntity g SET g.resolved = TRUE, g.resolvedAt = :resolvedAt
            WHERE g.transactionId = :transactionId AND g.resolved = FALSE
            """)
    int resolveOpenGaps(@Param("transactionId") String transactionId, @Param("resolvedAt") Instant resolvedAt);
}
