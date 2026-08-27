package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.OrderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Orders — ORDER_RECORD evidence source and the parent of {@code order_lines}. */
@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findByTransactionId(String transactionId);

    Page<OrderEntity> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    Optional<OrderEntity> findByMerchantIdAndExternalRef(String merchantId, String externalRef);

    List<OrderEntity> findByCustomerId(String customerId);
}
