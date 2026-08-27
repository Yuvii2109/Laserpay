package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.persistence.entity.DisputeEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Disputes ({@code GET /disputes?merchantId&status&reasonCode}). */
@Repository
public interface DisputeRepository extends JpaRepository<DisputeEntity, String> {

    Page<DisputeEntity> findByMerchantIdAndStatus(String merchantId, DisputeStatus status, Pageable pageable);

    Page<DisputeEntity> findByMerchantId(String merchantId, Pageable pageable);

    List<DisputeEntity> findByTransactionId(String transactionId);

    Page<DisputeEntity> findByMerchantIdAndReasonCode(String merchantId, DisputeReasonCode reasonCode, Pageable pageable);

    Optional<DisputeEntity> findByMerchantIdAndPspDisputeRef(String merchantId, String pspDisputeRef);

    /** Deadline sweep: still-open disputes whose representment window is closing. */
    List<DisputeEntity> findByStatusInAndDeadlineAtBefore(Collection<DisputeStatus> statuses, Instant before);

    long countByMerchantIdAndStatus(String merchantId, DisputeStatus status);
}
