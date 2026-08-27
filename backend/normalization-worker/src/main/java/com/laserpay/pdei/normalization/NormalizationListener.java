package com.laserpay.pdei.normalization;

import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.normalization.observability.KafkaTracing;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The worker's only Kafka entry point: {@code pdei.raw.events.v1} in, canonical events out.
 *
 * <p>Design decisions that are load-bearing:
 *
 * <ul>
 *   <li><strong>The value is consumed as {@code String}, not as a typed record.</strong> A
 *       deserialization failure inside the container is awkward to route (the record never reaches
 *       the listener with its body intact), and an unparseable body is one of the commonest real
 *       failures. Parsing here means the raw text is still available to put in the dead letter.</li>
 *   <li><strong>Acknowledgement is manual.</strong> The offset is committed only after the canonical
 *       event is durably published or the record is dead-lettered. At-least-once delivery plus
 *       idempotent processing is the contract; at-most-once would lose financial events.</li>
 *   <li><strong>The trace context is restored before any work happens</strong>, so every log line
 *       and every produced record carries the trace that started at the webhook.</li>
 * </ul>
 *
 * <p>Retry, backoff and dead-lettering are the error handler's job (see
 * {@code KafkaConsumerConfig}); this method only distinguishes "parse failure" (immediately fatal,
 * never retried) from "processing failure" (rethrown for the handler to schedule).
 */
@Component
public class NormalizationListener {

    private static final Logger log = LoggerFactory.getLogger(NormalizationListener.class);

    private final NormalizationService normalizationService;

    public NormalizationListener(NormalizationService normalizationService) {
        this.normalizationService = normalizationService;
    }

    @KafkaListener(
            id = "pdei-normalization-raw-events",
            topics = Topics.RAW_EVENTS,
            groupId = ConsumerGroups.PDEI_NORMALIZATION_WORKER,
            containerFactory = "rawEventListenerContainerFactory")
    public void onRawEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context context = KafkaTracing.extract(record.headers());
        KafkaTracing.inScope(context, header(record, EventHeaders.MERCHANT_ID),
                header(record, EventHeaders.CORRELATION_ID), () -> handle(record, ack));
    }

    private void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        RawEventEnvelope raw = parse(record);
        normalizationService.normalizeAndPublish(raw);
        ack.acknowledge();
    }

    /**
     * Parses the record value into a {@link RawEventEnvelope}.
     *
     * <p>Failures are wrapped in {@link ValidationException}, which the error handler treats as
     * non-retryable: malformed bytes will not become well-formed on a second attempt, so the record
     * goes straight to the DLQ with its original text preserved.
     */
    private RawEventEnvelope parse(ConsumerRecord<String, String> record) {
        String value = record.value();
        if (value == null || value.isBlank()) {
            throw new ValidationException("empty record value on " + record.topic() + "-"
                    + record.partition() + "@" + record.offset());
        }
        try {
            RawEventEnvelope raw = Json.read(value, RawEventEnvelope.class);
            if (raw == null) {
                throw new ValidationException("record value deserialized to null");
            }
            return raw;
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("unparseable raw event at {}-{}@{}: {}", record.topic(), record.partition(),
                    record.offset(), e.toString());
            throw new ValidationException("record value is not a RawEventEnvelope", e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : EventHeaders.decode(header.value());
    }
}
