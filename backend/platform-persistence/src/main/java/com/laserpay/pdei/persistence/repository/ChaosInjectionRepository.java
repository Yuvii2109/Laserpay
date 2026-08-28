package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.ChaosType;
import com.laserpay.pdei.persistence.entity.ChaosInjectionEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Chaos injection history ({@code GET /sim/v1/chaos}) - the evidence that failures were real. */
@Repository
public interface ChaosInjectionRepository extends JpaRepository<ChaosInjectionEntity, String> {

    List<ChaosInjectionEntity> findByRunIdOrderByInjectedAtDesc(String runId);

    List<ChaosInjectionEntity> findByTypeOrderByInjectedAtDesc(ChaosType type);

    Page<ChaosInjectionEntity> findAllByOrderByInjectedAtDesc(Pageable pageable);

    List<ChaosInjectionEntity> findByMerchantIdAndInjectedAtBetween(String merchantId, Instant from, Instant to);

    long countByTypeAndStatus(ChaosType type, String status);
}
