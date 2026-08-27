package com.laserpay.pdei.audit.consume;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
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
 * Consumes every domain topic - canonical, evidence, readiness, dispute and case events - and
 * derives an audit entry from each (PLATFORM-CONTRACT section 4).
 *
 * <p>This is what makes the trail <em>complete</em> rather than merely <em>diligent</em>. If the
 * audit log contained only what each service remembered to report, its gaps would be exactly the
 * places where a service had a bug - which is exactly where an auditor needs to look. Deriving an
 * entry from every fact on every topic removes that correlation.
 *
 * <p>The topic list is a bean rather than a literal so a targeted replay can narrow it
 * ({@code pdei.audit.consume.*}): during an investigation it is useful to replay one topic without
 * the others racing new entries into the same chains.
 *
 * <p>Ordering note: entries are appended in the order this consumer sees them, which for events
 * about one aggregate is their production order (partitions are keyed
 * {@code merchantId + ":" + aggregateId}), but across aggregates of one merchant is arbitrary. That
 * is fine and is why {@code occurredAt} is stored separately from chain position: the chain proves
 * <em>that</em> nothing was altered, {@code occurredAt} says <em>when</em> it happened.
 */
@Component
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final AuditIntake intake;
    private final DeadLetterPublisher deadLetters;

    public DomainEventConsumer(AuditIntake intake, DeadLetterPublisher deadLetters) {
        this.intake = Objects.requireNonNull(intake, "intake must not be null");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters must not be null");
    }

    @KafkaListener(
            id = "audit-domain-events",
            topics = "#{@auditDomainTopics}",
            groupId = ConsumerGroups.PDEI_AUDIT_SERVICE,
            containerFactory = "canonicalEventListenerContainerFactory")
    public void onDomainEvent(@Payload CanonicalEvent event,
                              @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
                              @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                              @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
                              Acknowledgment acknowledgment) {
        try {
            AuditIntake.Outcome outcome = intake.acceptDomainEvent(event);
            log.trace("domain event {} from {} -> {}",
                    event == null ? null : event.eventId(), topic, outcome);
        } catch (RuntimeException e) {
            deadLetters.publish(topic, partition == null ? -1 : partition,
                    offset == null ? -1L : offset,
                    event == null ? null : event.partitionKey(), event, e, 1);
        } finally {
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        }
    }
}
