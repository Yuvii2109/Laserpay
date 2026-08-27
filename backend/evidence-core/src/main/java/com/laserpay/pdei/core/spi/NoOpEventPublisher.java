package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback publisher used when no {@code KafkaTemplate} is on the context (unit tests, one-shot CLI
 * tooling). Logs instead of publishing so a missing broker never silently looks like a success.
 */
public class NoOpEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpEventPublisher.class);

    @Override
    public void publish(String topic, CanonicalEvent event) {
        log.debug("event publication skipped (no Kafka template): topic={} type={} aggregateId={}",
                topic, event == null ? null : event.eventType(), event == null ? null : event.aggregateId());
    }

    @Override
    public void publishAudit(AuditEvent event) {
        log.debug("audit publication skipped (no Kafka template): auditId={}",
                event == null ? null : event.auditId());
    }
}
