package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Transactions — the readiness unit of work.
 *
 * <p>{@link #searchByFilters} backs {@code GET /transactions?merchantId&band&from&to&page&size};
 * every filter is optional, which is why it is a JPQL query with null guards rather than a
 * combinatorial explosion of derived methods.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    Page<TransactionEntity> findByMerchantId(String merchantId, Pageable pageable);

    Page<TransactionEntity> findByMerchantIdAndReadinessBand(String merchantId, ReadinessBand band, Pageable pageable);

    List<TransactionEntity> findByMerchantIdAndOccurredAtBetween(String merchantId, Instant from, Instant to);

    List<TransactionEntity> findByCustomerId(String customerId);

    Page<TransactionEntity> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    long countByMerchantIdAndReadinessBand(String merchantId, ReadinessBand band);

    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:merchantId IS NULL OR t.merchantId = :merchantId)
              AND (:band       IS NULL OR t.readinessBand = :band)
              AND (:from       IS NULL OR t.occurredAt >= :from)
              AND (:to         IS NULL OR t.occurredAt <  :to)
            """)
    Page<TransactionEntity> searchByFilters(@Param("merchantId") String merchantId,
                                            @Param("band") ReadinessBand band,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            Pageable pageable);

    /** Transactions whose readiness has never been computed or is stale — sweep input. */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.readinessComputedAt IS NULL OR t.readinessComputedAt < :staleBefore
            ORDER BY t.occurredAt DESC
            """)
    List<TransactionEntity> findStaleReadiness(@Param("staleBefore") Instant staleBefore, Pageable pageable);
}
