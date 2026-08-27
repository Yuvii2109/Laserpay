package com.laserpay.pdei.statebuilder.dlq;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.DeadLetterEnvelope;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.statebuilder.observability.KafkaTracing;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Routes records this worker cannot process to {@code pdei.dlq.v1}.
 *
 * <p>Nothing is ever silently dropped (docs/event-catalog.md section 11). The envelope keeps the
 * exact Kafka coordinates - topic, partition, offset - plus the original payload, so a fix to an
 * adapter can be followed by a targeted replay rather than a guess.
 *
 * <p>The original payload is preserved even when it is not valid JSON: an unparseable body is
 * stored as a JSON string node. That case is common (a truncated webhook, a wrong content type) and
 * is exactly when the raw bytes matter most.
 *
 * <p>Publication is synchronous. A dead letter that is itself lost turns a visible failure into a
 * silent one, so this call blocks on the broker acknowledgement and re-throws on failure - the
 * error handler then declines to commit the offset and the record is redelivered.
 */
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String consumerGroup;
    private final String serviceName;
    private final MeterRegistry meterRegistry;
    private final long timeoutMillis;

    public DeadLetterPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               String consumerGroup,
                               String serviceName,
                               MeterRegistry meterRegistry,
                               long timeoutMillis) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumerGroup = consumerGroup;
        this.serviceName = serviceName;
        this.meterRegistry = meterRegistry;
        this.timeoutMillis = timeoutMillis <= 0 ? 10_000L : timeoutMillis;
    }

    /**
     * Publishes a dead letter for a failed record.
     *
     * @param attempt delivery attempt that produced the failure, taken from the
     *                {@code pdei-attempt} header when the retry topic pattern is in play
     */
    public void publish(ConsumerRecord<String, String> record, Throwable failure, int attempt) {
        DeadLetterEnvelope envelope = DeadLetterEnvelope.from(
                record.topic(),
                record.partition(),
                record.offset(),
                consumerGroup,
                failure,
                attempt,
                originalPayload(record.value()),
                Instant.now());

        ProducerRecord<String, Object> outbound =
                new ProducerRecord<>(Topics.DLQ, null, record.key(), envelope);
        copyHeader(record, outbound, EventHeaders.EVENT_ID);
        copyHeader(record, outbound, EventHeaders.EVENT_TYPE);
        copyHeader(record, outbound, EventHeaders.MERCHANT_ID);
        copyHeader(record, outbound, EventHeaders.CORRELATION_ID);
        outbound.headers().add(EventHeaders.ATTEMPT,
                Integer.toString(attempt).getBytes(StandardCharsets.UTF_8));
        KafkaTracing.inject(outbound.headers());

        try {
            kafkaTemplate.send(outbound).get(timeoutMillis, TimeUnit.MILLISECONDS);
            count();
            log.error("dead-lettered {}-{}@{} key={} failureClass={} message={}", record.topic(),
                    record.partition(), record.offset(), record.key(), envelope.failureClass(),
                    envelope.failureMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing a dead letter", e);
        } catch (Exception e) {
            // Losing a dead letter is worse than reprocessing: surface it so the offset is not
            // committed and the record comes back.
            throw new IllegalStateException("failed to publish dead letter for " + record.topic()
                    + "-" + record.partition() + "@" + record.offset(), e);
        }
    }

    /**
     * The record value as a JSON tree, or a JSON string node when it does not parse. An unparseable
     * body is itself a common dead-letter cause and must survive to the DLQ intact.
     */
    private JsonNode originalPayload(String value) {
        if (value == null) {
            return Json.mapper().nullNode();
        }
        try {
            return Json.readTree(value);
        } catch (RuntimeException e) {
            return Json.mapper().getNodeFactory().textNode(value);
        }
    }

    private void copyHeader(ConsumerRecord<String, String> from, ProducerRecord<String, Object> to,
                            String name) {
        var header = from.headers().lastHeader(name);
        if (header != null && header.value() != null) {
            to.headers().add(name, header.value());
        }
    }

    private void count() {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                    MetricNames.Tag.SERVICE, serviceName,
                    MetricNames.Tag.TYPE, "unknown",
                    MetricNames.Tag.OUTCOME, MetricNames.Outcome.DEAD_LETTERED).increment();
        } catch (RuntimeException e) {
            log.debug("failed to record dead-letter metric: {}", e.toString());
        }
    }
}
