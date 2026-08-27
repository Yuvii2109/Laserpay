package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.AiAdmissionLogEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Admission-control decision log (contract 9.4); backs the observability funnel. */
@Repository
public interface AiAdmissionLogRepository extends JpaRepository<AiAdmissionLogEntity, String> {

    List<AiAdmissionLogEntity> findByCaseIdOrderByDecidedAtDesc(String caseId);

    Page<AiAdmissionLogEntity> findByMerchantId(String merchantId, Pageable pageable);

    List<AiAdmissionLogEntity> findByMerchantIdAndDecidedAtBetween(String merchantId, Instant from, Instant to);

    long countByAdmittedAndDecidedAtAfter(boolean admitted, Instant after);

    /** admitted -> count, for {@code pdei_ai_admission_total{decision}} over a window. */
    @Query(value = """
            SELECT a.admitted AS admitted, count(*) AS total
            FROM pdei.ai_admission_log a
            WHERE a.merchant_id = :merchantId AND a.decided_at >= :from
            GROUP BY a.admitted
            """, nativeQuery = true)
    List<Object[]> admissionBreakdown(@Param("merchantId") String merchantId, @Param("from") Instant from);
}
