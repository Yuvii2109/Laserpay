package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.RefundEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Refunds. A PROCESSED refund is the decisive fact for CREDIT_NOT_PROCESSED and
 * PAID_BY_OTHER_MEANS disputes, so the sum below is read by the readiness engine.
 */
@Repository
public interface RefundRepository extends JpaRepository<RefundEntity, String> {

    List<RefundEntity> findByTransactionId(String transactionId);

    Page<RefundEntity> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    List<RefundEntity> findByTransactionIdAndStatus(String transactionId, String status);

    Optional<RefundEntity> findByPspReference(String pspReference);

    List<RefundEntity> findByMerchantIdAndProcessedAtBetween(String merchantId, Instant from, Instant to);

    /** Total refunded minor units for a transaction. Returns 0 when nothing was refunded. */
    @Query("""
            SELECT COALESCE(SUM(r.amount.amountMinor), 0) FROM RefundEntity r
            WHERE r.transactionId = :transactionId AND r.status = 'PROCESSED'
            """)
    long sumProcessedAmountMinor(@Param("transactionId") String transactionId);
}
