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

    /**
     * Optional filters, expressed so that PostgreSQL can type every bind parameter.
     *
     * <p>The obvious form - {@code (:merchantId IS NULL OR t.merchantId = :merchantId)} - is what
     * this query used, and it does not work here. Hibernate emits a bare {@code ?} for the
     * {@code IS NULL} test, PostgreSQL has nothing to infer a type from, and the statement fails
     * to prepare with
     *
     * <pre>ERROR: could not determine data type of parameter $5</pre>
     *
     * so {@code GET /api/v1/transactions} returned 500 for every request. The comparison half was
     * never the problem: {@code t.occurredAt >= :from} takes its type from the column.
     *
     * <p>Passing an explicit "this filter is absent" flag keeps each parameter beside the column
     * that types it. The flags are derived in {@link #searchByFilters} so callers still pass plain
     * nullable values, and the idiom is ordinary JPQL rather than a database-specific cast.
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:anyMerchant = TRUE OR t.merchantId    = :merchantId)
              AND (:anyBand     = TRUE OR t.readinessBand = :band)
              AND (:anyFrom     = TRUE OR t.occurredAt   >= :from)
              AND (:anyTo       = TRUE OR t.occurredAt   <  :to)
            """)
    Page<TransactionEntity> searchByFilters(@Param("anyMerchant") boolean anyMerchant,
                                            @Param("merchantId") String merchantId,
                                            @Param("anyBand") boolean anyBand,
                                            @Param("band") ReadinessBand band,
                                            @Param("anyFrom") boolean anyFrom,
                                            @Param("from") Instant from,
                                            @Param("anyTo") boolean anyTo,
                                            @Param("to") Instant to,
                                            Pageable pageable);

    /** Nullable-argument form; absent filters become flags the query above can type. */
    default Page<TransactionEntity> searchByFilters(String merchantId, ReadinessBand band,
                                                    Instant from, Instant to, Pageable pageable) {
        return searchByFilters(merchantId == null, merchantId,
                band == null, band,
                from == null, from,
                to == null, to,
                pageable);
    }

    /** Transactions whose readiness has never been computed or is stale — sweep input. */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE t.readinessComputedAt IS NULL OR t.readinessComputedAt < :staleBefore
            ORDER BY t.occurredAt DESC
            """)
    List<TransactionEntity> findStaleReadiness(@Param("staleBefore") Instant staleBefore, Pageable pageable);
}
