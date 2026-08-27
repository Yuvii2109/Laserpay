package com.laserpay.pdei.ingestion.publisher;

import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.DeadLetterEnvelope;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes accepted raw events to {@code pdei.raw.events.v1}, and dead-letters what it cannot
 * publish.
 *
 * <p><strong>Partition key.</strong> Always {@code merchantId + ":" + aggregateId}
 * (PLATFORM-CONTRACT section 4). This is what guarantees that every event about one payment lands
 * on one partition, in order, and is therefore processed in order by a single consumer instance.
 * The aggregate id is resolved by the caller ({@code IngestionService}) from the submission, the
 * body, and finally the idempotency key - see {@code AggregateIdResolver}.
 *
 * <p><strong>Synchronous by choice.</strong> The send is awaited up to
 * {@code ingestion.publisher.send-timeout}. An HTTP 202 that means "queued in a buffer that may
 * still drop it" is a lie a financial platform cannot afford; here {@code accepted} means the
 * broker acknowledged the record.
 *
 * <p><strong>Dead letters.</strong> A failed send produces a {@link DeadLetterEnvelope} on
 * {@code pdei.dlq.v1} carrying the whole original envelope, so the fact survives even when the
 * target topic did not accept it. {@code partition} and {@code offset} are {@code -1} because the
 * record never reached a partition - the coordinates a consumer-side dead letter would carry do not
 * exist for a producer-side failure. If the DLQ send also fails, the exception propagates and the
 * event is reported as rejected: the caller still holds the fact and can retry.
 *
 * <p><strong>Headers.</strong> Every record carries the contract's header set so a consumer can
 * dedupe, route or dead-letter without deserialising a body that may be exactly what failed to
 * parse. {@code traceparent} is propagated from the inbound HTTP request when present, so a trace
 * in Tempo spans the adapter, ingestion, normalisation and everything downstream.
 */
@Component
public class RawEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RawEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IngestionProperties properties;
    private final Clocks clock;

    public RawEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                             IngestionProperties properties,
                             Clocks clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes one raw event.
     *
     * @param envelope      the event to publish
     * @param aggregateId   aggregate this fact is about; forms the second half of the partition key
     * @param correlationId correlation id for the {@code pdei-correlation-id} header
     * @param traceparent   inbound W3C trace context, or null
     * @throws UpstreamUnavailableException when the record could not be acknowledged by the broker
     */
    public void publish(RawEventEnvelope envelope, String aggregateId, String correlationId, String traceparent) {
        String key = partitionKey(envelope, aggregateId);
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(Topics.RAW_EVENTS, null, null, key, envelope);
        applyHeaders(record.headers(), envelope, correlationId, traceparent);

        try {
            kafkaTemplate.send(record)
                    .get(properties.getPublisher().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Published raw event {} ({}/{}) to {} key={}", envelope.rawEventId(),
                    envelope.sourceSystem(), envelope.sourceEventType(), Topics.RAW_EVENTS, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("kafka",
                    "interrupted while publishing " + envelope.rawEventId(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.error("Failed to publish raw event {} to {}: {}",
                    envelope.rawEventId(), Topics.RAW_EVENTS, cause.toString());
            deadLetter(envelope, key, cause);
            throw new UpstreamUnavailableException("kafka",
                    "could not publish " + envelope.rawEventId() + " to " + Topics.RAW_EVENTS, cause);
        }
    }

    /**
     * Writes the failed event to {@code pdei.dlq.v1}. Never throws: the caller is already failing
     * and a dead-letter failure must not mask the original cause.
     *
     * @return true when the dead letter was acknowledged
     */
    public boolean deadLetter(RawEventEnvelope envelope, String key, Throwable failure) {
        if (!properties.getPublisher().isDlqEnabled()) {
            return false;
        }
        try {
            DeadLetterEnvelope dead = DeadLetterEnvelope.from(
                    Topics.RAW_EVENTS,
                    -1,   // producer-side failure: the record never reached a partition
                    -1L,  // ... and therefore has no offset
                    ConsumerGroups.PDEI_INGESTION_SERVICE,
                    failure,
                    1,
                    Json.tree(envelope),
                    clock.now());
            kafkaTemplate.send(Topics.DLQ, key, dead)
                    .get(properties.getPublisher().getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.warn("Dead-lettered raw event {} to {} (failure: {})",
                    envelope.rawEventId(), Topics.DLQ, failure == null ? "unknown" : failure.toString());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while dead-lettering raw event {}", envelope.rawEventId());
            return false;
        } catch (Exception e) {
            log.error("Could not dead-letter raw event {} to {}: {}. The fact is now only held by the "
                            + "caller - it must retry.",
                    envelope.rawEventId(), Topics.DLQ, e.toString());
            return false;
        }
    }

    /**
     * {@code merchantId + ":" + aggregateId}, with the envelope's own key as the last resort. The
     * fallback is {@link RawEventEnvelope#partitionKey()}, which keys by idempotency key: still
     * merchant-scoped, still stable across redeliveries of the same fact, so ordering per fact holds
     * even when the aggregate could not be identified.
     */
    static String partitionKey(RawEventEnvelope envelope, String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            return envelope.partitionKey();
        }
        return envelope.merchantId() + ":" + aggregateId;
    }

    private static void applyHeaders(Headers headers,
                                     RawEventEnvelope envelope,
                                     String correlationId,
                                     String traceparent) {
        headers.add(EventHeaders.EVENT_ID, EventHeaders.encode(envelope.rawEventId()));
        headers.add(EventHeaders.EVENT_TYPE, EventHeaders.encode(envelope.sourceEventType()));
        headers.add(EventHeaders.MERCHANT_ID, EventHeaders.encode(envelope.merchantId()));
        headers.add(EventHeaders.CORRELATION_ID,
                EventHeaders.encode(correlationId == null ? envelope.rawEventId() : correlationId));
        headers.add(EventHeaders.SCHEMA_VERSION,
                EventHeaders.encode(Integer.toString(CanonicalEvent.CURRENT_SCHEMA_VERSION)));
        headers.add(EventHeaders.ATTEMPT, EventHeaders.encode("1"));
        if (traceparent != null && !traceparent.isBlank()) {
            headers.add(EventHeaders.TRACEPARENT, EventHeaders.encode(traceparent));
        }
    }
}
