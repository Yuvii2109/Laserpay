package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.PaymentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Payments; {@code findByPspAndPspReference} is the natural-key lookup used during ingestion. */
@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, String> {

    List<PaymentEntity> findByTransactionId(String transactionId);

    List<PaymentEntity> findByTransactionIdOrderByOccurredAtAsc(String transactionId);

    Page<PaymentEntity> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    Optional<PaymentEntity> findByPspAndPspReference(String psp, String pspReference);

    List<PaymentEntity> findByDeviceFingerprint(String deviceFingerprint);
}
