package com.laserpay.pdei.api.service;

import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.RefundEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.persistence.repository.OrderRepository;
import com.laserpay.pdei.persistence.repository.RefundRepository;
import com.laserpay.pdei.persistence.repository.ShipmentRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an order, shipment or refund id to the transaction that owns it, so the AI tool routes
 * can answer from the canonical {@code TransactionFacts} projection rather than from a second,
 * divergent mapping of the same rows.
 */
@Component
@Transactional(readOnly = true)
public class JpaEntityOwnerResolver implements AiToolsService.EntityOwnerResolver {

    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final RefundRepository refunds;

    public JpaEntityOwnerResolver(OrderRepository orders, ShipmentRepository shipments,
                                  RefundRepository refunds) {
        this.orders = orders;
        this.shipments = shipments;
        this.refunds = refunds;
    }

    @Override
    public Optional<String> transactionIdForOrder(String orderId) {
        return blank(orderId) ? Optional.empty()
                : orders.findById(orderId).map(OrderEntity::getTransactionId);
    }

    @Override
    public Optional<String> transactionIdForShipment(String shipmentId) {
        return blank(shipmentId) ? Optional.empty()
                : shipments.findById(shipmentId).map(ShipmentEntity::getTransactionId);
    }

    @Override
    public Optional<String> transactionIdForRefund(String refundId) {
        return blank(refundId) ? Optional.empty()
                : refunds.findById(refundId).map(RefundEntity::getTransactionId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
