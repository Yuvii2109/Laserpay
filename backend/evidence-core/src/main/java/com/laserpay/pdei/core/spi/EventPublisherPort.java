package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;

/**
 * Outbound event port. Implementations publish to Kafka with the mandatory partition key
 * {@code merchantId + ":" + aggregateId} (platform contract 4).
 */
public interface EventPublisherPort {

    /** Publish a canonical event to the given topic. Must not throw on transport failure. */
    void publish(String topic, CanonicalEvent event);

    /** Publish an audit event to {@code pdei.audit.events.v1}. */
    void publishAudit(AuditEvent event);
}
