package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.CommunicationEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Customer communications — CUSTOMER_COMMUNICATION evidence source and timeline input. */
@Repository
public interface CommunicationRepository extends JpaRepository<CommunicationEntity, String> {

    List<CommunicationEntity> findByTransactionIdOrderByOccurredAtAsc(String transactionId);

    List<CommunicationEntity> findByMerchantIdAndOccurredAtBetween(String merchantId, Instant from, Instant to);

    List<CommunicationEntity> findByCustomerIdOrderByOccurredAtDesc(String customerId);

    List<CommunicationEntity> findByTransactionIdAndDirection(String transactionId, String direction);

    /** FTS over subject/sender/recipient/body, maintained by trg_communications_search_vector. */
    @Query(value = """
            SELECT c.* FROM pdei.communications c
            WHERE (CAST(:merchantId AS text) IS NULL OR c.merchant_id = CAST(:merchantId AS text))
              AND c.search_vector @@ websearch_to_tsquery('english', CAST(:tsQuery AS text))
            ORDER BY ts_rank(c.search_vector, websearch_to_tsquery('english', CAST(:tsQuery AS text))) DESC,
                     c.occurred_at DESC
            """, nativeQuery = true)
    List<CommunicationEntity> search(@Param("tsQuery") String tsQuery,
                                     @Param("merchantId") String merchantId,
                                     Pageable pageable);
}
