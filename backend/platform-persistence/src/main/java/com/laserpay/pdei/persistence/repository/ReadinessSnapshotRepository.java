package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.persistence.entity.ReadinessSnapshotEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Readiness snapshot history; the newest row is the answer to GET /transactions/{id}/readiness. */
@Repository
public interface ReadinessSnapshotRepository extends JpaRepository<ReadinessSnapshotEntity, String> {

    Optional<ReadinessSnapshotEntity> findTopByTransactionIdOrderByComputedAtDesc(String transactionId);

    Optional<ReadinessSnapshotEntity> findTopByTransactionIdAndReasonCodeOrderByComputedAtDesc(
            String transactionId, DisputeReasonCode reasonCode);

    List<ReadinessSnapshotEntity> findByTransactionIdOrderByComputedAtDesc(String transactionId);

    Page<ReadinessSnapshotEntity> findByMerchantIdAndBand(String merchantId, ReadinessBand band, Pageable pageable);

    List<ReadinessSnapshotEntity> findByTransactionIdAndCurrentTrue(String transactionId);

    /** Flips previous snapshots to historical before a freshly computed one is inserted. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ReadinessSnapshotEntity s SET s.current = FALSE
            WHERE s.transactionId = :transactionId AND s.current = TRUE
            """)
    int markPreviousAsHistorical(@Param("transactionId") String transactionId);

    /** Readiness distribution for the control tower (current snapshots only). */
    @Query(value = """
            SELECT s.band AS band, count(*) AS total
            FROM pdei.readiness_snapshots s
            WHERE s.merchant_id = :merchantId AND s.is_current
            GROUP BY s.band
            """, nativeQuery = true)
    List<Object[]> bandDistribution(@Param("merchantId") String merchantId);
}
