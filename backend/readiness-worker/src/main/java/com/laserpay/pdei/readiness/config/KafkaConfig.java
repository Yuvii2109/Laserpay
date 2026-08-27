package com.laserpay.pdei.readiness.config;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.readiness.consume.DeadLetterPublisher;
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
 * Kafka wiring for the readiness worker.
 *
 * <p>Everything on the wire is the canonical envelope of PLATFORM-CONTRACT section 3, serialised
 * with the shared {@code Json.mapper()} - the same mapper that produces ISO-8601 instants and
 * canonical JSON for hashing everywhere else in the platform. Using the shared mapper rather than a
 * locally configured one is what keeps {@code occurredAt} parseable by every service.
 *
 * <p><strong>Manual acknowledgement.</strong> {@code AckMode.MANUAL_IMMEDIATE}: the listener
 * acknowledges after the intake has claimed the event, never before. Combined with the
 * {@code processed_events} claim, redelivery after a crash is safe and duplicate delivery is
 * invisible.
 *
 * <p><strong>Deserialisation failures cannot poison a partition.</strong>
 * {@link ErrorHandlingDeserializer} converts a malformed record into a null payload plus a
 * deserialisation exception rather than throwing inside the consumer loop, and the error handler
 * routes it to {@code pdei.dlq.v1}. Partitions are keyed by {@code merchantId + ":" + aggregateId},
 * so a single unparseable record that stalled the partition would stall one merchant's whole
 * readiness pipeline.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Retry a transient failure a few times before dead-lettering. */
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long RETRY_MAX_ATTEMPTS = 3L;

    // --- producer -------------------------------------------------------------------------------

    /**
     * Declared explicitly rather than left to Boot so the type is exactly
     * {@code KafkaTemplate<String, Object>}: {@code evidence-core}'s {@code KafkaEventPublisher} is
     * injected through an {@code ObjectProvider} of that parameterisation, and the partition key is
     * always a String.
     */
    @Bean
    public ProducerFactory<String, Object> pdeiProducerFactory(KafkaProperties properties) {
        Map<String, Object> configs = properties.buildProducerProperties(null);
        return new DefaultKafkaProducerFactory<>(configs, new StringSerializer(),
                new JsonSerializer<>(Json.mapper()));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // --- consumer -------------------------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, CanonicalEvent> canonicalEventConsumerFactory(
            KafkaProperties properties) {
        Map<String, Object> configs = properties.buildConsumerProperties(null);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, ConsumerGroups.PDEI_READINESS_WORKER);
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<CanonicalEvent> valueDeserializer =
                new JsonDeserializer<>(CanonicalEvent.class, Json.mapper(), false);
        valueDeserializer.addTrustedPackages("com.laserpay.pdei.*");

        return new DefaultKafkaConsumerFactory<>(configs,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    /**
     * The container factory both listeners use. One factory, one consumer group, two topics
     * (docs/SHARED-LIBRARY-API.md section 1.5).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CanonicalEvent>
            canonicalEventListenerContainerFactory(ConsumerFactory<String, CanonicalEvent> consumerFactory,
                                                   KafkaProperties properties,
                                                   DeadLetterPublisher deadLetters) {
        ConcurrentKafkaListenerContainerFactory<String, CanonicalEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency(properties));

        ContainerProperties containerProperties = factory.getContainerProperties();
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // A worker must start before its topics exist: the whole stack comes up at once in compose.
        containerProperties.setMissingTopicsFatal(false);

        factory.setCommonErrorHandler(deadLetteringErrorHandler(deadLetters));
        return factory;
    }

    /**
     * Bounded retry, then dead-letter and move on.
     *
     * <p>A record that still fails after the last attempt is published to {@code pdei.dlq.v1} with
     * the coordinates needed to replay it, and the offset advances. The alternative - retrying
     * forever - trades one broken event for a permanently stalled merchant.
     */
    private DefaultErrorHandler deadLetteringErrorHandler(DeadLetterPublisher deadLetters) {
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, RETRY_MAX_ATTEMPTS);

        DefaultErrorHandler handler = new DefaultErrorHandler((consumerRecord, exception) -> {
            Object key = consumerRecord.key();
            deadLetters.publish(consumerRecord.topic(), consumerRecord.partition(),
                    consumerRecord.offset(), key == null ? null : key.toString(),
                    consumerRecord.value(), exception, (int) RETRY_MAX_ATTEMPTS + 1);
        }, backOff);

        // A payload that will not deserialise will never deserialise: retrying it is pure latency.
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                com.laserpay.pdei.common.error.ValidationException.class,
                com.laserpay.pdei.common.error.UnknownEventTypeException.class);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        log.debug("readiness kafka error handler: {} retries then dead-letter", RETRY_MAX_ATTEMPTS);
        return handler;
    }

    private static int concurrency(KafkaProperties properties) {
        Integer configured = properties.getListener().getConcurrency();
        return configured == null || configured < 1 ? 3 : configured;
    }
}
