package com.laserpay.pdei.docproc.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.docproc.service.DocProcStats;
import com.laserpay.pdei.docproc.service.DocumentProcessingService;
import com.laserpay.pdei.docproc.service.ProcessingOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Kafka entry point: EVIDENCE events in, extracted text out.
 *
 * <p><strong>Topics.</strong> Subscribes to both {@code pdei.evidence.events.v1} and
 * {@code pdei.canonical.events.v1}. The contract lists this service as a consumer of the
 * canonical topic (section 4) while {@code Topics.forEventType(EvidenceAdded)} routes evidence
 * events to the evidence topic - so listening to only one of the two would either contradict the
 * contract or never see an {@code EvidenceAdded}. Both are safe together because the idempotency
 * claim is keyed on {@code (eventId, consumerGroup)}, not on topic: an event that somehow
 * appeared on both is processed exactly once.
 *
 * <p><strong>Loop safety.</strong> This service also <em>produces</em> to
 * {@code pdei.evidence.events.v1}. Its own events carry
 * {@code payload.emittedBy = document-processor-service} and are skipped on sight. The
 * SKIPPED_UNCHANGED short-circuit in {@link DocumentProcessingService} is the second line of
 * defence: even if a self-emitted event were processed, unchanged bytes produce no new event.
 *
 * <p><strong>Late, duplicate and out-of-order delivery.</strong> All three are normal here. The
 * handler is a function of the evidence row's current state, never of the event's payload or its
 * position in the stream, so replaying yesterday's {@code EvidenceAdded} today converges on the
 * same result.
 */
@Component
public class EvidenceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EvidenceEventConsumer.class);

    private final DocumentProcessingService processingService;
    private final IdempotencyGuard idempotency;
    private final DeadLetterPublisher deadLetters;
    private final DocProcStats stats;
    private final Clocks clock;

    public EvidenceEventConsumer(DocumentProcessingService processingService,
                                 IdempotencyGuard idempotency,
                                 DeadLetterPublisher deadLetters,
                                 DocProcStats stats,
                                 Clocks clock) {
        this.processingService = processingService;
        this.idempotency = idempotency;
        this.deadLetters = deadLetters;
        this.stats = stats;
        this.clock = clock;
    }

    /**
     * One record. Transactional so the {@code processed_events} claim commits together with the
     * evidence row update: a crash between the two would otherwise either lose the extraction or
     * skip it on redelivery.
     */
    @KafkaListener(
            id = "docproc-evidence",
            topics = {"#{T(com.laserpay.pdei.common.kafka.Topics).EVIDENCE_EVENTS}",
                    "#{T(com.laserpay.pdei.common.kafka.Topics).CANONICAL_EVENTS}"},
            groupId = "#{T(com.laserpay.pdei.common.kafka.ConsumerGroups).PDEI_DOCUMENT_PROCESSOR_SERVICE}",
            containerFactory = "docprocKafkaListenerContainerFactory",
            autoStartup = "${pdei.docproc.consumer-enabled:true}")
    @Transactional
    public void onEvidenceEvent(ConsumerRecord<String, String> record) {
        Instant startedAt = clock.now();
        CanonicalEvent event;
        try {
            event = Json.read(record.value(), CanonicalEvent.class);
        } catch (RuntimeException e) {
            // A malformed envelope will still be malformed on the tenth retry.
            deadLetters.publish(record, "MALFORMED_ENVELOPE", e, attemptOf(record));
            return;
        }

        stats.eventReceived();

        if (!isExtractionTrigger(event)) {
            return;
        }
        if (isSelfEmitted(event)) {
            stats.eventSelfEmitted();
            log.debug("skipping self-emitted event {} for evidence {}",
                    event.eventId(), event.aggregateId());
            return;
        }
        if (!idempotency.claim(event.eventId())) {
            stats.eventDuplicate();
            log.debug("duplicate event {} ({}), already processed", event.eventId(), event.eventType());
            return;
        }

        try {
            ProcessingOutcome outcome =
                    processingService.processEvidence(event.aggregateId(), event.eventId(), false);
            stats.recordOutcome(outcome, event.eventType().name(),
                    Duration.between(startedAt, clock.now()));
        } catch (RuntimeException e) {
            // The processed_events claim rolls back with this transaction, so redelivery will
            // legitimately retry. Dead-lettering as well gives an operator the coordinates.
            stats.recordFailure(event.eventType().name());
            deadLetters.publish(record, "HANDLER_ERROR", e, attemptOf(record));
            throw e;
        }
    }

    /**
     * Only {@code EvidenceAdded} carries a new artifact. {@code EvidenceExpired} and
     * {@code EvidenceInvalidated} are lifecycle transitions on an artifact whose bytes have not
     * changed, and re-parsing them would burn CPU to write identical text.
     */
    private static boolean isExtractionTrigger(CanonicalEvent event) {
        return event.eventType() == EventType.EvidenceAdded;
    }

    private static boolean isSelfEmitted(CanonicalEvent event) {
        JsonNode emittedBy = event.payload().get(DocumentProcessingService.PAYLOAD_EMITTED_BY);
        return emittedBy != null
                && DocumentProcessingService.EMITTED_BY.equals(emittedBy.asText());
    }

    /** Delivery attempt from the {@code pdei-attempt} header; 1 when the header is absent. */
    private static int attemptOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EventHeaders.ATTEMPT);
        if (header == null || header.value() == null || header.value().length == 0) {
            return 1;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
