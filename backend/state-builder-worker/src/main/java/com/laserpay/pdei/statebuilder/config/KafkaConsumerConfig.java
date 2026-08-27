package com.laserpay.pdei.statebuilder.config;

import com.laserpay.pdei.common.error.PolicyViolationException;
import com.laserpay.pdei.common.error.UnknownEventTypeException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.statebuilder.dlq.DeadLetterPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring: canonical-event consumer, evidence/dispute/DLQ producer, error handling.
 *
 * <p>The {@code KafkaTemplate<String, Object>} declared here is used by three things:
 * {@link com.laserpay.pdei.statebuilder.forward.EventForwarder}, {@link DeadLetterPublisher}, and
 * {@code evidence-core}'s {@code KafkaEventPublisher} - which is how {@code EvidenceAdded} reaches
 * {@code pdei.evidence.events.v1} when this worker derives an artifact. One template, one
 * serializer, one set of delivery guarantees.
 *
 * <p><strong>Retry classification.</strong> Failures that will fail identically on the next attempt
 * (malformed body, unknown event type, a payload the domain rejects) are dead-lettered on the first
 * exception. Everything else - a database blip, an optimistic-locking clash between two consumer
 * threads touching the same transaction row, a broker hiccup - retries with exponential backoff.
 * Optimistic-locking failures are the common retry case and almost always succeed on the second
 * attempt, which is exactly what the backoff is for.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);
    private static final String SERVICE = "state-builder-worker";

    // --- producer -------------------------------------------------------------------------------

    @Bean
    public ProducerFactory<String, Object> pdeiProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(Json.mapper());
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), valueSerializer);
    }

    /**
     * Shared by the forwarder, the dead-letter publisher and evidence-core's event publisher.
     * Declared explicitly so the generic signature matches what {@code CoreAutoConfiguration}
     * resolves ({@code ObjectProvider<KafkaTemplate<String, Object>>}).
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                                   ObjectProvider<MeterRegistry> meterRegistries,
                                                   StateBuilderProperties properties) {
        return new DeadLetterPublisher(kafkaTemplate, ConsumerGroups.PDEI_STATE_BUILDER_WORKER,
                SERVICE, meterRegistries.getIfAvailable(), properties.getPublishTimeout().toMillis());
    }

    // --- consumer -------------------------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, String> canonicalEventConsumerFactory(
            KafkaProperties kafkaProperties, StateBuilderProperties properties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, ConsumerGroups.PDEI_STATE_BUILDER_WORKER);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getMaxPollRecords());
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> canonicalEventListenerContainerFactory(
            ConsumerFactory<String, String> canonicalEventConsumerFactory,
            DefaultErrorHandler stateBuilderErrorHandler,
            StateBuilderProperties properties) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(canonicalEventConsumerFactory);
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(stateBuilderErrorHandler);

        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Trace context is propagated explicitly through KafkaTracing, so behaviour is identical
        // with and without an OTel agent and exactly one component is responsible for it.
        container.setObservationEnabled(false);
        return factory;
    }

    // --- error handling -------------------------------------------------------------------------

    @Bean
    public DefaultErrorHandler stateBuilderErrorHandler(DeadLetterPublisher deadLetterPublisher,
                                                        StateBuilderProperties properties) {
        StateBuilderProperties.Retry retry = properties.getRetry();
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(Math.max(0, retry.getMaxAttempts() - 1));
        backOff.setInitialInterval(retry.getInitialInterval().toMillis());
        backOff.setMultiplier(retry.getMultiplier());
        backOff.setMaxInterval(retry.getMaxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> recover(record, exception, deadLetterPublisher), backOff);

        handler.addNotRetryableExceptions(
                ValidationException.class,
                UnknownEventTypeException.class,
                PolicyViolationException.class);

        handler.setAckAfterHandle(true);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }

    @SuppressWarnings("unchecked")
    private void recover(ConsumerRecord<?, ?> record, Exception exception,
                         DeadLetterPublisher deadLetterPublisher) {
        ConsumerRecord<String, String> typed = (ConsumerRecord<String, String>) record;
        log.error("recovering {}-{}@{} after failure: {}", record.topic(), record.partition(),
                record.offset(), exception.toString());
        deadLetterPublisher.publish(typed, unwrap(exception), attemptOf(typed));
    }

    private static int attemptOf(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(EventHeaders.ATTEMPT);
        if (header == null || header.value() == null) {
            return 1;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Unwraps the container's wrapper so the dead letter names the failure that actually happened. */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null
                && current.getClass().getName().startsWith("org.springframework.kafka")
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? failure : current;
    }
}
