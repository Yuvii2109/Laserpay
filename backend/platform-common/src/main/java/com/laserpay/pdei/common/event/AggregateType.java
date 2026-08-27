package com.laserpay.pdei.common.event;

/**
 * The kind of aggregate a canonical event is about (PLATFORM-CONTRACT section 3).
 *
 * <p>Together with {@code aggregateId} this forms half of the Kafka partition key, which is what
 * guarantees per-entity ordering across the whole event backbone.
 */
public enum AggregateType {
    MERCHANT,
    CUSTOMER,
    TRANSACTION,
    PAYMENT,
    ORDER,
    SHIPMENT,
    DELIVERY,
    REFUND,
    COMMUNICATION,
    EVIDENCE,
    POLICY,
    DISPUTE,
    CASE
}
