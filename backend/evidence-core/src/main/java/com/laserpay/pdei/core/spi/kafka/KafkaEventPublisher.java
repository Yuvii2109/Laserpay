package com.laserpay.pdei.core.spi.kafka;

import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Kafka implementation of {@link EventPublisherPort}.
 *
 * <p>The partition key is always {@code merchantId + ":" + aggregateId} (platform contract 4) so
 * every event about one aggregate lands on one partition and downstream consumers see it in order.
 * Headers carry the ids that let a consumer dedupe without deserialising the body.</p>
 *
 * <p>Publication failures are logged, not thrown: the domain state is already committed at this
 * point, and re-throwing would roll back a correct write because a broker hiccuped. Consumers
 * tolerate gaps by recomputing from state, and the audit log records the intent regardless.</p>
 */
public class KafkaEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, CanonicalEvent event) {
        if (event == null) {
            return;
        }
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(topic, null, event.partitionKey(), event);
        header(record, EventHeaders.EVENT_ID, event.eventId());
        header(record, EventHeaders.EVENT_TYPE, event.eventType() == null ? null : event.eventType().name());
        header(record, EventHeaders.MERCHANT_ID, event.merchantId());
        header(record, EventHeaders.CORRELATION_ID, event.correlationId());
        header(record, EventHeaders.SCHEMA_VERSION, String.valueOf(event.schemaVersion()));
        send(topic, record);
    }

    @Override
    public void publishAudit(AuditEvent event) {
        if (event == null) {
            return;
        }
        String key = (event.merchantId() == null ? "SYSTEM" : event.merchantId()) + ":" + event.entityId();
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(Topics.AUDIT_EVENTS, null, key, event);
        header(record, EventHeaders.EVENT_ID, event.auditId());
        header(record, EventHeaders.EVENT_TYPE, "AuditRecorded");
        header(record, EventHeaders.MERCHANT_ID, event.merchantId());
        header(record, EventHeaders.CORRELATION_ID, event.correlationId());
        send(Topics.AUDIT_EVENTS, record);
    }

    private void send(String topic, ProducerRecord<String, Object> record) {
        try {
            kafkaTemplate.send(record).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("failed to publish to topic={} key={}: {}", topic, record.key(),
                            failure.toString());
                }
            });
        } catch (RuntimeException e) {
            log.error("failed to enqueue publication to topic={} key={}: {}", topic, record.key(), e.toString());
        }
    }

    private static void header(ProducerRecord<String, Object> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
