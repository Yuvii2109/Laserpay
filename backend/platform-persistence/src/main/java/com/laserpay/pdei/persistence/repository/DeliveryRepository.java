package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.DeliveryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Deliveries — DELIVERY_PROOF evidence source. Conflicting {@code deliveredAt} values across
 * two deliveries of the same transaction are exactly what ContradictionDetector looks for.
 */
@Repository
public interface DeliveryRepository extends JpaRepository<DeliveryEntity, String> {

    List<DeliveryEntity> findByShipmentId(String shipmentId);

    List<DeliveryEntity> findByTransactionId(String transactionId);

    List<DeliveryEntity> findByTransactionIdAndStatus(String transactionId, String status);

    Optional<DeliveryEntity> findFirstByShipmentIdOrderByDeliveredAtDesc(String shipmentId);

    long countByTransactionIdAndSignatureCapturedTrue(String transactionId);
}
