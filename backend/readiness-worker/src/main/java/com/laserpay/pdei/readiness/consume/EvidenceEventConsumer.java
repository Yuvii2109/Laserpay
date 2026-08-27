package com.laserpay.pdei.readiness.consume;

import com.laserpay.pdei.common.event.CanonicalEvent;
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
 * Consumes {@code pdei.evidence.events.v1} - {@code EvidenceAdded}, {@code EvidenceExpired},
 * {@code EvidenceInvalidated} produced by state-builder-worker and document-processor-service
 * (PLATFORM-CONTRACT section 4).
 *
 * <p>This is the primary trigger of the whole worker: a change in the evidence set is, by
 * definition, a change in readiness.
 *
 * <p>Offsets are acknowledged manually and only after the intake has claimed the event and queued
 * the recomputation. Acknowledging first would let a crash between ack and claim lose the trigger
 * silently; acknowledging after, at worst, redelivers - which the idempotency claim absorbs.
 *
 * <p>Failures acknowledge too, having dead-lettered the event first. Refusing to advance the offset
 * would stall the partition, and partitions are keyed per aggregate, so one poison event would
 * freeze readiness for every transaction of that merchant.
 */
@Component
public class EvidenceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EvidenceEventConsumer.class);

    private final EventIntake intake;
    private final DeadLetterPublisher deadLetters;

    public EvidenceEventConsumer(EventIntake intake, DeadLetterPublisher deadLetters) {
        this.intake = Objects.requireNonNull(intake, "intake must not be null");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters must not be null");
    }

    @KafkaListener(
            id = "readiness-evidence-events",
            topics = Topics.EVIDENCE_EVENTS,
            groupId = ConsumerGroups.PDEI_READINESS_WORKER,
            containerFactory = "canonicalEventListenerContainerFactory")
    public void onEvidenceEvent(@Payload CanonicalEvent event,
                                @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
                                @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                                @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
                                Acknowledgment acknowledgment) {
        try {
            EventIntake.Outcome outcome = intake.accept(event);
            log.trace("evidence event {} -> {}", event == null ? null : event.eventId(), outcome);
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
