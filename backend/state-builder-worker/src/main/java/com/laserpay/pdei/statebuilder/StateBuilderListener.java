package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.common.error.UnknownEventTypeException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.statebuilder.observability.KafkaTracing;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The worker's Kafka entry point: {@code pdei.canonical.events.v1} in, projections and derived
 * events out.
 *
 * <p>Mirrors normalization-worker's listener deliberately - values as {@code String} so an
 * unparseable body can still be dead-lettered with its original text, manual acknowledgement so the
 * offset moves only after the work is durable, and trace context restored before any work happens.
 * Two workers behaving identically at the transport layer is worth more than each being locally
 * optimal.
 *
 * <p>Retry, backoff and dead-lettering belong to the error handler in {@code KafkaConsumerConfig}.
 * This method distinguishes only "the bytes are not an event" (non-retryable) from "handling failed"
 * (rethrown for the handler to schedule).
 */
@Component
public class StateBuilderListener {

    private static final Logger log = LoggerFactory.getLogger(StateBuilderListener.class);

    private final StateBuilderService stateBuilderService;

    public StateBuilderListener(StateBuilderService stateBuilderService) {
        this.stateBuilderService = stateBuilderService;
    }

    @KafkaListener(
            id = "pdei-state-builder-canonical-events",
            topics = Topics.CANONICAL_EVENTS,
            groupId = ConsumerGroups.PDEI_STATE_BUILDER_WORKER,
            containerFactory = "canonicalEventListenerContainerFactory")
    public void onCanonicalEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context context = KafkaTracing.extract(record.headers());
        KafkaTracing.inScope(context, header(record, EventHeaders.MERCHANT_ID),
                header(record, EventHeaders.CORRELATION_ID), () -> handle(record, ack));
    }

    private void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        CanonicalEvent event = parse(record);
        stateBuilderService.handle(event);
        ack.acknowledge();
    }

    /**
     * Parses the record value into a {@link CanonicalEvent}.
     *
     * <p>An unknown {@code eventType} surfaces as {@code UnknownEventTypeException} from
     * {@code EventType.fromWire} during deserialization. That is treated as poison and dead-lettered
     * rather than allowed to block the partition: a newer producer may legitimately emit types this
     * build does not know, and the record stays replayable once this build catches up.
     */
    private CanonicalEvent parse(ConsumerRecord<String, String> record) {
        String value = record.value();
        if (value == null || value.isBlank()) {
            throw new ValidationException("empty record value on " + record.topic() + "-"
                    + record.partition() + "@" + record.offset());
        }
        try {
            CanonicalEvent event = Json.read(value, CanonicalEvent.class);
            if (event == null) {
                throw new ValidationException("record value deserialized to null");
            }
            return event;
        } catch (ValidationException | UnknownEventTypeException e) {
            // Already a precise, non-retryable failure class: let it reach the dead letter intact
            // so the DLQ says what actually went wrong rather than "not a CanonicalEvent".
            log.warn("rejecting canonical event at {}-{}@{}: {}", record.topic(),
                    record.partition(), record.offset(), e.toString());
            throw e;
        } catch (RuntimeException e) {
            log.warn("unparseable canonical event at {}-{}@{}: {}", record.topic(),
                    record.partition(), record.offset(), e.toString());
            throw new ValidationException("record value is not a CanonicalEvent", e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : EventHeaders.decode(header.value());
    }
}
