package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.persistence.entity.DisputeCaseEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Representment cases — the case queue ({@code GET /cases?status&merchantId}). */
@Repository
public interface DisputeCaseRepository extends JpaRepository<DisputeCaseEntity, String> {

    List<DisputeCaseEntity> findByDisputeId(String disputeId);

    Optional<DisputeCaseEntity> findTopByDisputeIdOrderByOpenedAtDesc(String disputeId);

    Page<DisputeCaseEntity> findByMerchantIdAndStatus(String merchantId, CaseStatus status, Pageable pageable);

    Page<DisputeCaseEntity> findByMerchantId(String merchantId, Pageable pageable);

    Page<DisputeCaseEntity> findByStatus(CaseStatus status, Pageable pageable);

    List<DisputeCaseEntity> findByStatusIn(Collection<CaseStatus> statuses);

    Optional<DisputeCaseEntity> findByWorkflowId(String workflowId);

    List<DisputeCaseEntity> findByStatusInAndDeadlineAtBefore(Collection<CaseStatus> statuses, Instant before);

    /** Swimlane counts for the case queue UI. */
    @Query(value = """
            SELECT c.status AS status, count(*) AS total
            FROM pdei.dispute_cases c
            WHERE c.merchant_id = :merchantId
            GROUP BY c.status
            """, nativeQuery = true)
    List<Object[]> countByStatus(@Param("merchantId") String merchantId);
}
