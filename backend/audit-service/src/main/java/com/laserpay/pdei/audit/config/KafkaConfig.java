package com.laserpay.pdei.audit.config;

import com.laserpay.pdei.audit.consume.DeadLetterPublisher;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka wiring for the audit service.
 *
 * <p>Two payload shapes, so two consumer factories and two container factories:
 * {@code AuditEvent} on {@code pdei.audit.events.v1}, and {@code CanonicalEvent} on every domain
 * topic. Both share the single consumer group {@code pdei-audit-service}, so offsets, lag and replay
 * bookmarks stay coherent across all six topics (docs/SHARED-LIBRARY-API.md section 1.5).
 *
 * <p><strong>Concurrency is 1, deliberately.</strong> Every append contends for its merchant's chain
 * head, and the database resolves collisions by rejecting the loser's insert. That is correct but it
 * is not free: more consumer threads means more conflicts, more retries and no more throughput,
 * because the chain is inherently serial per merchant. Scale by adding replicas (which partition the
 * merchants between them) rather than threads inside one.
 *
 * <p>Both payloads are (de)serialised with the shared {@code Json.mapper()}. For audit records this
 * is not merely convenient: the stored hash is taken over the canonical JSON of the same field
 * names, so a mapper that renamed or reordered anything would produce entries that fail their own
 * verification.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long RETRY_MAX_ATTEMPTS = 3L;

    /** One append at a time per replica: the chain is serial per merchant anyway. */
    private static final int CHAIN_CONCURRENCY = 1;

    // --- producer (DLQ only) --------------------------------------------------------------------

    @Bean
    public ProducerFactory<String, Object> pdeiProducerFactory(KafkaProperties properties) {
        Map<String, Object> configs = properties.buildProducerProperties(null);
        return new DefaultKafkaProducerFactory<>(configs, new StringSerializer(),
                new JsonSerializer<>(Json.mapper()));
    }

    /**
     * The audit service produces to exactly one topic: {@code pdei.dlq.v1}. It deliberately does not
     * republish audit records - it is the sink, and echoing what it consumes would be a loop
     * ({@code pdei.core.audit.publish-to-kafka=false} in {@code application.yml}).
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // --- consumers ------------------------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, AuditEvent> auditEventConsumerFactory(KafkaProperties properties) {
        return consumerFactory(properties, AuditEvent.class);
    }

    @Bean
    public ConsumerFactory<String, CanonicalEvent> canonicalEventConsumerFactory(
            KafkaProperties properties) {
        return consumerFactory(properties, CanonicalEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent>
            auditEventListenerContainerFactory(ConsumerFactory<String, AuditEvent> consumerFactory,
                                               DeadLetterPublisher deadLetters) {
        return containerFactory(consumerFactory, deadLetters);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CanonicalEvent>
            canonicalEventListenerContainerFactory(
                    ConsumerFactory<String, CanonicalEvent> consumerFactory,
                    DeadLetterPublisher deadLetters) {
        return containerFactory(consumerFactory, deadLetters);
    }

    private <T> ConsumerFactory<String, T> consumerFactory(KafkaProperties properties, Class<T> type) {
        Map<String, Object> configs = properties.buildConsumerProperties(null);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, ConsumerGroups.PDEI_AUDIT_SERVICE);
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(type, Json.mapper(), false);
        valueDeserializer.addTrustedPackages("com.laserpay.pdei.*");

        return new DefaultKafkaConsumerFactory<>(configs,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> containerFactory(
            ConsumerFactory<String, T> consumerFactory, DeadLetterPublisher deadLetters) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(CHAIN_CONCURRENCY);

        ContainerProperties containerProperties = factory.getContainerProperties();
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        containerProperties.setMissingTopicsFatal(false);

        factory.setCommonErrorHandler(deadLetteringErrorHandler(deadLetters));
        return factory;
    }

    /**
     * Bounded retry, then dead-letter.
     *
     * <p>A {@code ValidationException} is never retried: it means the record violates a
     * {@code V8__audit.sql} check constraint and will violate it identically on every attempt. It
     * goes straight to {@code pdei.dlq.v1}, where the record survives intact for replay once the
     * producing service is fixed.
     */
    private DefaultErrorHandler deadLetteringErrorHandler(DeadLetterPublisher deadLetters) {
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, RETRY_MAX_ATTEMPTS);

        DefaultErrorHandler handler = new DefaultErrorHandler((consumerRecord, exception) -> {
            Object key = consumerRecord.key();
            deadLetters.publish(consumerRecord.topic(), consumerRecord.partition(),
                    consumerRecord.offset(), key == null ? null : key.toString(),
                    consumerRecord.value(), exception, (int) RETRY_MAX_ATTEMPTS + 1);
        }, backOff);

        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                com.laserpay.pdei.common.error.ValidationException.class,
                com.laserpay.pdei.common.error.UnknownEventTypeException.class);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        log.debug("audit kafka error handler: {} retries then dead-letter", RETRY_MAX_ATTEMPTS);
        return handler;
    }
}
