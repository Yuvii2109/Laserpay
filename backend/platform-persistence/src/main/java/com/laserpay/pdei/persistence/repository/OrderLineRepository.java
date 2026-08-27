package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.OrderLineEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Order lines; {@code digitalGood} lines change which evidence a dispute can ever require. */
@Repository
public interface OrderLineRepository extends JpaRepository<OrderLineEntity, String> {

    List<OrderLineEntity> findByOrderIdOrderByLineNumberAsc(String orderId);

    Optional<OrderLineEntity> findByOrderIdAndLineNumber(String orderId, int lineNumber);

    List<OrderLineEntity> findByOrderIdIn(java.util.Collection<String> orderIds);

    long countByOrderIdAndDigitalGoodTrue(String orderId);
}
