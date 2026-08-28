package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Shipments - SHIPPING_RECORD evidence source; multiple shipments per order are normal. */
@Repository
public interface ShipmentRepository extends JpaRepository<ShipmentEntity, String> {

    List<ShipmentEntity> findByTransactionId(String transactionId);

    List<ShipmentEntity> findByOrderId(String orderId);

    Optional<ShipmentEntity> findByCarrierAndTrackingNumber(String carrier, String trackingNumber);

    List<ShipmentEntity> findByTrackingNumber(String trackingNumber);

    Page<ShipmentEntity> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    List<ShipmentEntity> findByTransactionIdInAndStatusIn(Collection<String> transactionIds, Collection<String> statuses);
}
