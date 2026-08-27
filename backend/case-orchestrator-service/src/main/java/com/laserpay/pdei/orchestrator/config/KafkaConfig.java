package com.laserpay.pdei.orchestrator.config;

import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.orchestrator.listener.DeadLetterPublisher;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
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
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring: {@code pdei.dispute.events.v1} in, {@code pdei.case.events.v1} and
 * {@code pdei.audit.events.v1} out.
 *
 * <p><b>Consumers read {@code String}, not typed objects.</b> The listener parses the JSON itself
 * with {@link Json#mapper()}. That keeps deserialisation failures inside our own code, where they
 * become a dead letter with a readable reason, instead of failing inside the container where the
 * record is harder to inspect. It also means a producer adding a field never breaks this consumer.</p>
 *
 * <p><b>Producers write with the shared mapper</b> and no Jackson type headers: the payload on the
 * wire is exactly the canonical envelope of contract section 3, readable by the Python and
 * TypeScript sides.</p>
 *
 * <p>Manual acknowledgement: the offset is committed only after the workflow has been started or
 * signalled, so a crash mid-handling redelivers rather than silently skips. Redelivery is safe -
 * the listener deduplicates on {@code eventId} and the workflow id makes a duplicate start a no-op.</p>
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Referenced by {@code @KafkaListener(containerFactory = ...)}. */
    public static final String LISTENER_CONTAINER_FACTORY = "pdeiKafkaListenerContainerFactory";

    private static final long RETRY_INITIAL_INTERVAL_MS = 1_000L;
    private static final double RETRY_MULTIPLIER = 2.0d;
    private static final long RETRY_MAX_INTERVAL_MS = 30_000L;
    private static final int RETRY_MAX_ATTEMPTS = 5;

    @Bean
    public ProducerFactory<String, Object> pdeiProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.putIfAbsent(ProducerConfig.CLIENT_ID_CONFIG,
                ConsumerGroups.PDEI_CASE_ORCHESTRATOR_SERVICE + "-producer");

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(Json.mapper());
        // No __TypeId__ header: the canonical envelope is a cross-language contract, not a Java class.
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> pdeiConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.putIfAbsent(ConsumerConfig.GROUP_ID_CONFIG,
                ConsumerGroups.PDEI_CASE_ORCHESTRATOR_SERVICE);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new StringDeserializer());
    }

    @Bean(LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> pdeiKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DeadLetterPublisher deadLetterPublisher) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // One consumer thread: dispute events for a case must be applied in order, and the workflow
        // itself is the concurrency mechanism. More threads here would buy nothing.
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(pdeiErrorHandler(deadLetterPublisher));
        return factory;
    }

    /**
     * Retry with backoff, then dead-letter. A record that cannot be handled after five attempts is a
     * data or contract problem; blocking the partition on it would stall every other case.
     */
    private DefaultErrorHandler pdeiErrorHandler(DeadLetterPublisher deadLetterPublisher) {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(RETRY_MAX_ATTEMPTS - 1);
        backOff.setInitialInterval(RETRY_INITIAL_INTERVAL_MS);
        backOff.setMultiplier(RETRY_MULTIPLIER);
        backOff.setMaxInterval(RETRY_MAX_INTERVAL_MS);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> deadLetterPublisher.publish(record, exception,
                        RETRY_MAX_ATTEMPTS),
                backOff);
        handler.setAckAfterHandle(true);
        log.info("kafka error handler: {} attempts with exponential backoff, then {}",
                RETRY_MAX_ATTEMPTS, Topics.DLQ);
        return handler;
    }
}
