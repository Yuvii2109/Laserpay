package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.CustomerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Customers, always scoped by merchant (no cross-tenant reads). */
@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {

    Page<CustomerEntity> findByMerchantId(String merchantId, Pageable pageable);

    List<CustomerEntity> findByMerchantIdAndEmail(String merchantId, String email);

    Optional<CustomerEntity> findByMerchantIdAndExternalRef(String merchantId, String externalRef);

    long countByMerchantId(String merchantId);
}
