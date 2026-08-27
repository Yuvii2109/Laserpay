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
 * Consumes {@code pdei.canonical.events.v1} for entity state changes: payments, orders, shipments,
 * deliveries, refunds, communications and disputes (PLATFORM-CONTRACT sections 4 and 7).
 *
 * <p>These matter because readiness is not only "which documents exist" but "do they still prove
 * what this transaction now is". A refund changes what has to be proven; a delivery changes which
 * requirement a shipping record satisfies; a dispute fixes the reason code the score must be
 * computed against.
 *
 * <p>Most traffic on this topic is irrelevant to readiness, so {@link EventIntake} filters by event
 * type before doing anything expensive, and unresolvable aggregates are dropped rather than guessed
 * at. Both are counted, so a topic that suddenly stops producing recomputations is visible in
 * {@code pdei_events_processed_total{outcome="skipped"}} rather than silent.
 *
 * <p>Shares the consumer group with the evidence listener: one group per service
 * (docs/SHARED-LIBRARY-API.md section 1.5), so offsets, lag and replay bookmarks stay coherent
 * across both topics.
 */
@Component
public class CanonicalEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(CanonicalEventConsumer.class);

    private final EventIntake intake;
    private final DeadLetterPublisher deadLetters;

    public CanonicalEventConsumer(EventIntake intake, DeadLetterPublisher deadLetters) {
        this.intake = Objects.requireNonNull(intake, "intake must not be null");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters must not be null");
    }

    @KafkaListener(
            id = "readiness-canonical-events",
            topics = Topics.CANONICAL_EVENTS,
            groupId = ConsumerGroups.PDEI_READINESS_WORKER,
            containerFactory = "canonicalEventListenerContainerFactory")
    public void onCanonicalEvent(@Payload CanonicalEvent event,
                                 @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
                                 @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                                 @Header(name = KafkaHeaders.OFFSET, required = false) Long offset,
                                 Acknowledgment acknowledgment) {
        try {
            EventIntake.Outcome outcome = intake.accept(event);
            log.trace("canonical event {} -> {}", event == null ? null : event.eventId(), outcome);
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
