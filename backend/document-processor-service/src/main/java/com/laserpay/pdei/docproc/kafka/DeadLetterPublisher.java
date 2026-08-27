package com.laserpay.pdei.docproc.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.DeadLetterEnvelope;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Routes a record this service cannot handle to {@code pdei.dlq.v1}.
 *
 * <p>Dead-lettering rather than retrying forever is the point: a malformed envelope will be just
 * as malformed on the tenth attempt, and a consumer that keeps retrying it stops consuming
 * everything behind it on that partition. The envelope keeps the coordinates
 * ({@code topic/partition/offset}) needed to replay the record once the cause is fixed, which is
 * what the simulator's {@code REPLAY_EVENTS} chaos type exercises.
 *
 * <p>The stack trace is stored as a digest, not as text: DLQ records are retained and shipped to
 * Loki, and a full trace per record is a lot of bytes for information a correlated log line
 * already carries.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates;
    private final Clocks clock;

    public DeadLetterPublisher(ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates, Clocks clock) {
        this.kafkaTemplates = kafkaTemplates;
        this.clock = clock;
    }

    /**
     * @param record       the record that could not be handled
     * @param failureClass short classifier, e.g. {@code MALFORMED_ENVELOPE} or {@code HANDLER_ERROR}
     * @param cause        what went wrong
     * @param attempt      delivery attempt number, from the {@code pdei-attempt} header
     */
    public void publish(ConsumerRecord<String, String> record, String failureClass,
                        Throwable cause, int attempt) {
        KafkaTemplate<String, Object> template = kafkaTemplates.getIfAvailable();
        if (template == null) {
            log.error("no KafkaTemplate available; dropping dead letter from {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), cause.toString());
            return;
        }

        DeadLetterEnvelope envelope = new DeadLetterEnvelope(
                record.topic(),
                record.partition(),
                record.offset(),
                ConsumerGroups.PDEI_DOCUMENT_PROCESSOR_SERVICE,
                failureClass,
                abbreviate(cause.toString()),
                stackTraceDigest(cause),
                clock.now(),
                attempt,
                originalPayload(record.value()));

        try {
            // The object, not Json.write(...): the producer's value serializer is
            // JsonSerializer, so a String here would be published as a quoted literal.
            template.send(Topics.DLQ, record.key(), envelope);
            log.warn("dead-lettered {}-{}@{} ({}): {}", record.topic(), record.partition(),
                    record.offset(), failureClass, cause.toString());
        } catch (RuntimeException e) {
            log.error("could not publish dead letter for {}-{}@{}", record.topic(),
                    record.partition(), record.offset(), e);
        }
    }

    /** Keeps the raw string when it is not parseable JSON, so nothing about the record is lost. */
    private static JsonNode originalPayload(String value) {
        if (value == null) {
            return Json.mapper().nullNode();
        }
        try {
            return Json.readTree(value);
        } catch (RuntimeException e) {
            return Json.mapper().getNodeFactory().textNode(abbreviate(value));
        }
    }

    private static String stackTraceDigest(Throwable cause) {
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        return Hashes.sha256Hex(writer.toString());
    }

    private static String abbreviate(String value) {
        return value.length() > 4000 ? value.substring(0, 4000) : value;
    }
}
