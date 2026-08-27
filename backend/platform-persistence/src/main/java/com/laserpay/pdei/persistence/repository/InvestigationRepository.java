package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.persistence.entity.InvestigationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Investigations ({@code GET /investigations/{investigationId}}, Case X-Ray AI panel). */
@Repository
public interface InvestigationRepository extends JpaRepository<InvestigationEntity, String> {

    List<InvestigationEntity> findByCaseIdOrderByRequestedAtDesc(String caseId);

    Optional<InvestigationEntity> findTopByCaseIdOrderByCompletedAtDesc(String caseId);

    Page<InvestigationEntity> findByMerchantIdAndClassification(
            String merchantId, InvestigationClassification classification, Pageable pageable);

    List<InvestigationEntity> findByTransactionId(String transactionId);

    List<InvestigationEntity> findByDisputeId(String disputeId);

    long countByMerchantIdAndDeterministic(String merchantId, boolean deterministic);

    /** Funnel input: how many investigations actually reached a model in a window. */
    @Query("""
            SELECT count(i) FROM InvestigationEntity i
            WHERE i.merchantId = :merchantId AND i.deterministic = FALSE
              AND i.requestedAt >= :from AND i.requestedAt < :to
            """)
    long countModelBacked(@Param("merchantId") String merchantId,
                          @Param("from") Instant from,
                          @Param("to") Instant to);
}
