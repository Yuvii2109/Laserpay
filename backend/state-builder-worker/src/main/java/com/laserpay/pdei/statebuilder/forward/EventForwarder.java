package com.laserpay.pdei.statebuilder.forward;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.statebuilder.observability.KafkaTracing;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Re-publishes a canonical event onto a dedicated downstream topic, unchanged.
 *
 * <h2>Why fan-out happens here</h2>
 *
 * normalization-worker publishes everything to {@code pdei.canonical.events.v1} - one topic, one
 * producer, one ordering domain. But {@code case-orchestrator-service} only cares about disputes and
 * {@code readiness-worker} only cares about evidence, and making each of them consume and filter the
 * full canonical firehose would couple their scaling to total event volume rather than to the volume
 * they act on.
 *
 * <p>So this worker, which already reads every canonical event, forwards the relevant ones to
 * {@code pdei.dispute.events.v1} and {@code pdei.evidence.events.v1}. The event is forwarded
 * <em>identically</em>: same {@code eventId}, same partition key, same headers. Downstream
 * idempotency therefore works exactly as it does on the canonical topic, and a consumer reading both
 * topics sees one event, not two.
 *
 * <h2>Synchronous by design</h2>
 *
 * The send blocks on the broker acknowledgement inside the handling transaction. A dispute event
 * that fails to reach the orchestrator would mean a dispute nobody works - so the failure rolls the
 * projection write back and the event is redelivered, rather than being logged and forgotten.
 */
public class EventForwarder {

    private static final Logger log = LoggerFactory.getLogger(EventForwarder.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration timeout;

    public EventForwarder(KafkaTemplate<String, Object> kafkaTemplate, Duration timeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    /** Forwards {@code event} to {@code topic}, preserving identity, key and headers. */
    public void forward(String topic, CanonicalEvent event) {
        if (event == null || topic == null) {
            return;
        }
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(topic, null, event.partitionKey(), event);
        header(record, EventHeaders.EVENT_ID, event.eventId());
        header(record, EventHeaders.EVENT_TYPE, event.eventType().name());
        header(record, EventHeaders.MERCHANT_ID, event.merchantId());
        header(record, EventHeaders.CORRELATION_ID, event.correlationId());
        header(record, EventHeaders.SCHEMA_VERSION, Integer.toString(event.schemaVersion()));
        KafkaTracing.inject(record.headers());

        try {
            kafkaTemplate.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("forwarded {} {} to {}", event.eventType(), event.eventId(), topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted forwarding " + event.eventId() + " to " + topic, e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to forward " + event.eventType() + " " + event.eventId() + " to " + topic, e);
        }
    }

    private static void header(ProducerRecord<String, Object> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
