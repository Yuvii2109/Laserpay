package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.MerchantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Merchants ({@code GET /merchants}, {@code GET /merchants/{merchantId}}). */
@Repository
public interface MerchantRepository extends JpaRepository<MerchantEntity, String> {

    Page<MerchantEntity> findByStatus(String status, Pageable pageable);

    List<MerchantEntity> findByCountry(String country);

    Optional<MerchantEntity> findByLegalNameIgnoreCase(String legalName);

    /** Control-tower KPI block for {@code GET /merchants/{merchantId}/summary}. */
    @Query(value = """
            SELECT t.readiness_band AS band, count(*) AS total
            FROM pdei.transactions t
            WHERE t.merchant_id = :merchantId
            GROUP BY t.readiness_band
            """, nativeQuery = true)
    List<Object[]> readinessBandDistribution(@Param("merchantId") String merchantId);
}
