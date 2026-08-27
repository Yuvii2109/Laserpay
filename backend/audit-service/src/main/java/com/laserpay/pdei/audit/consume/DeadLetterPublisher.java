package com.laserpay.pdei.audit.consume;

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
 * Routes an unstorable audit record to {@code pdei.dlq.v1} (PLATFORM-CONTRACT section 4).
 *
 * <p>Dead-lettering an audit record is the least bad of three options, and it is worth being
 * explicit about why:
 *
 * <ul>
 *   <li><strong>Retry forever</strong> stalls the partition. Audit partitions carry every merchant's
 *       history, so one malformed record would freeze the entire trail.</li>
 *   <li><strong>Coerce it to fit</strong> means this service edits the content of an audit record -
 *       precisely what it exists to make impossible.</li>
 *   <li><strong>Dead-letter it</strong> keeps the record intact and replayable, advances the offset,
 *       and makes the producer's contract violation loudly visible.</li>
 * </ul>
 *
 * <p>A dead letter is therefore an operational alarm, not a discard: {@code pdei.dlq.v1} holds the
 * original payload verbatim and the coordinates needed to replay it once the producer is fixed.
 */
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clocks clock;

    public DeadLetterPublisher(KafkaTemplate<String, Object> kafkaTemplate, Clocks clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Never throws: a failure here would replace a data problem with a consumer crash. */
    public void publish(String originalTopic, int partition, long offset, String partitionKey,
                        Object payload, Throwable failure, int attempt) {
        if (kafkaTemplate == null) {
            log.error("no kafka template: dropping audit dead letter from {}-{}@{}: {}",
                    originalTopic, partition, offset, String.valueOf(failure));
            return;
        }
        try {
            DeadLetterEnvelope envelope = new DeadLetterEnvelope(
                    originalTopic,
                    partition,
                    offset,
                    ConsumerGroups.PDEI_AUDIT_SERVICE,
                    failure == null ? "Unknown" : failure.getClass().getName(),
                    failure == null ? "unknown failure" : String.valueOf(failure.getMessage()),
                    stackTraceDigest(failure),
                    clock.now(),
                    attempt,
                    payload == null ? null : Json.tree(payload));

            ProducerRecord<String, Object> record =
                    new ProducerRecord<>(Topics.DLQ, null, partitionKey, envelope);
            record.headers().add(EventHeaders.EVENT_TYPE, EventHeaders.encode("DeadLetter"));
            record.headers().add(EventHeaders.ATTEMPT, EventHeaders.encode(String.valueOf(attempt)));
            kafkaTemplate.send(record);

            log.error("dead-lettered audit record from {}-{}@{} after {} attempts: {}",
                    originalTopic, partition, offset, attempt, String.valueOf(failure));
        } catch (RuntimeException e) {
            log.error("could not publish audit dead letter for {}-{}@{}: {}",
                    originalTopic, partition, offset, e.toString());
        }
    }

    /** Stable short fingerprint so identical failures group for triage. */
    static String stackTraceDigest(Throwable failure) {
        if (failure == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return Hashes.sha256Hex(writer.toString()).substring(0, 16);
    }
}
