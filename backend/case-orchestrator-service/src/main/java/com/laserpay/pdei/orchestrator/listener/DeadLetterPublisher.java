package com.laserpay.pdei.orchestrator.listener;

import com.laserpay.pdei.common.event.DeadLetterEnvelope;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Last stop for a dispute event this service cannot handle.
 *
 * <p>The envelope carries enough to replay the record by hand: the original topic, partition and
 * offset, the consumer group, a failure class and message, a digest of the stack trace and the raw
 * payload. The stack trace is stored as a <em>digest</em> rather than in full so that the DLQ stays
 * readable and so that two occurrences of the same bug are visibly the same bug.</p>
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clocks clock;

    public DeadLetterPublisher(KafkaTemplate<String, Object> kafkaTemplate, Clocks clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    public void publish(ConsumerRecord<?, ?> record, Exception failure, int attempt) {
        DeadLetterEnvelope envelope = new DeadLetterEnvelope(
                record.topic(),
                record.partition(),
                record.offset(),
                ConsumerGroups.PDEI_CASE_ORCHESTRATOR_SERVICE,
                failure == null ? "UnknownFailure" : failure.getClass().getName(),
                failure == null ? "unknown" : String.valueOf(failure.getMessage()),
                stackTraceDigest(failure),
                clock.now(),
                attempt,
                payloadOf(record));

        log.error("dead-lettering {}-{}@{} after {} attempt(s): {}", record.topic(),
                record.partition(), record.offset(), attempt, envelope.failureMessage());
        try {
            kafkaTemplate.send(Topics.DLQ, String.valueOf(record.key()), envelope);
        } catch (RuntimeException e) {
            // If even the DLQ is unreachable the log line above is the record of what happened.
            log.error("could not publish dead letter for {}-{}@{}: {}", record.topic(),
                    record.partition(), record.offset(), e.toString());
        }
    }

    /** Raw JSON when the value parses, otherwise the value as a text node - never nothing. */
    private static com.fasterxml.jackson.databind.JsonNode payloadOf(ConsumerRecord<?, ?> record) {
        Object value = record.value();
        if (value == null) {
            return Json.mapper().nullNode();
        }
        if (value instanceof String text) {
            try {
                return Json.readTree(text);
            } catch (RuntimeException e) {
                return Json.mapper().getNodeFactory().textNode(text);
            }
        }
        return Json.tree(value);
    }

    /** First 16 hex characters of the sha256 of the stack trace: stable, short, groupable. */
    private static String stackTraceDigest(Exception failure) {
        if (failure == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return Hashes.sha256Hex(writer.toString()).substring(0, 16);
    }
}
