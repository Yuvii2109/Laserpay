package com.laserpay.pdei.audit.consume;

import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Consumes {@code pdei.audit.events.v1} - the topic every service in the platform publishes to when
 * it changes something (PLATFORM-CONTRACT section 4).
 *
 * <p>These are the <em>explicit</em> audit records: a producer reporting "I invalidated evidence
 * EV-123, here is the before state and the after state". They carry information no derived record
 * could reconstruct, which is why they are stored as reported rather than regenerated.
 *
 * <p>Acknowledgement is manual and happens after the append. A crash between append and ack causes
 * a redelivery, which the idempotency claim and the {@code audit_id} conflict clause both absorb; a
 * crash between ack and append would lose an audit entry, which nothing absorbs.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditIntake intake;
    private final DeadLetterPublisher deadLetters;

    public AuditEventConsumer(AuditIntake intake, DeadLetterPublisher deadLetters) {
        this.intake = Objects.requireNonNull(intake, "intake must not be null");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters must not be null");
    }

    @KafkaListener(
            id = "audit-reported-events",
            topics = Topics.AUDIT_EVENTS,
            groupId = ConsumerGroups.PDEI_AUDIT_SERVICE,
            autoStartup = "${pdei.audit.consume.audit-topic:true}",
            containerFactory = "auditEventListenerContainerFactory")
    public void onAuditEvent(@Payload AuditEvent event,
                             @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
                             @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                             @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
                             Acknowledgment acknowledgment) {
        try {
            AuditIntake.Outcome outcome = intake.acceptAuditEvent(event);
            log.trace("audit event {} -> {}", event == null ? null : event.auditId(), outcome);
        } catch (RuntimeException e) {
            deadLetters.publish(topic, partition == null ? -1 : partition,
                    offset == null ? -1L : offset, partitionKey(event), event, e, 1);
        } finally {
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        }
    }

    /** Same key {@code KafkaEventPublisher} uses for audit records: merchant plus entity. */
    private static String partitionKey(AuditEvent event) {
        if (event == null) {
            return null;
        }
        String merchantId = event.merchantId() == null ? "SYSTEM" : event.merchantId();
        return merchantId + ":" + event.entityId();
    }
}
