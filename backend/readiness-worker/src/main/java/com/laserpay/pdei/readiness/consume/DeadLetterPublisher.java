package com.laserpay.pdei.readiness.consume;

import com.laserpay.pdei.common.event.DeadLetterEnvelope;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

/**
 * Routes an event this worker cannot process to {@code pdei.dlq.v1}
 * (PLATFORM-CONTRACT section 4).
 *
 * <p>Dead-lettering is the last resort, taken only after the retry policy is exhausted. The
 * alternative - letting the exception propagate forever - stalls the partition and, because
 * partitions are keyed by {@code merchantId + ":" + aggregateId}, would stall one merchant's entire
 * readiness pipeline behind a single malformed event.
 *
 * <p>The envelope records the coordinates needed to replay the event after the bug is fixed
 * (topic, partition, offset, consumer group) plus a stack trace <em>digest</em> rather than the
 * trace itself: the digest groups identical failures for triage without turning the DLQ into a log
 * shipper.
 */
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clocks clock;

    public DeadLetterPublisher(KafkaTemplate<String, Object> kafkaTemplate, Clocks clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Publish a dead letter. Never throws: a failure here would replace a recoverable data problem
     * with an unrecoverable consumer crash.
     */
    public void publish(String originalTopic, int partition, long offset, String partitionKey,
                        Object payload, Throwable failure, int attempt) {
        if (kafkaTemplate == null) {
            log.error("no kafka template: dropping dead letter from {}-{}@{}: {}",
                    originalTopic, partition, offset, String.valueOf(failure));
            return;
        }
        try {
            DeadLetterEnvelope envelope = new DeadLetterEnvelope(
                    originalTopic,
                    partition,
                    offset,
                    ConsumerGroups.PDEI_READINESS_WORKER,
                    failure == null ? "Unknown" : failure.getClass().getName(),
                    failure == null ? "unknown failure" : String.valueOf(failure.getMessage()),
                    stackTraceDigest(failure),
                    clock.now(),
                    attempt,
                    payload == null ? null : Json.tree(payload));

            ProducerRecord<String, Object> record =
                    new ProducerRecord<>(Topics.DLQ, null, partitionKey, envelope);
            record.headers().add(EventHeaders.EVENT_TYPE,
                    EventHeaders.encode("DeadLetter"));
            record.headers().add(EventHeaders.ATTEMPT, EventHeaders.encode(String.valueOf(attempt)));
            kafkaTemplate.send(record);

            log.error("dead-lettered {}-{}@{} after {} attempts: {}",
                    originalTopic, partition, offset, attempt, String.valueOf(failure));
        } catch (RuntimeException e) {
            log.error("could not publish dead letter for {}-{}@{}: {}",
                    originalTopic, partition, offset, e.toString());
        }
    }

    /**
     * Stable short fingerprint of a stack trace: the same bug always produces the same digest, so
     * failures group without storing megabytes of duplicate traces.
     */
    static String stackTraceDigest(Throwable failure) {
        if (failure == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return Hashes.sha256Hex(writer.toString()).substring(0, 16);
    }
}
