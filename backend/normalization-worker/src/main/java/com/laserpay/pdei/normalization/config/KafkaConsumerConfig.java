package com.laserpay.pdei.normalization.config;

import com.laserpay.pdei.common.error.UnknownEventTypeException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.normalization.adapter.UnmappableEventException;
import com.laserpay.pdei.normalization.dlq.DeadLetterPublisher;
import com.laserpay.pdei.normalization.support.MonetaryPrecisionException;
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
import org.springframework.util.backoff.ExponentialBackOffWithMaxRetries;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring: raw-event consumer, canonical/DLQ producer, error handling.
 *
 * <p><strong>Serialization.</strong> Values are consumed as {@code String} and produced with a
 * {@code JsonSerializer} bound to the shared {@code Json.mapper()}. Using the shared mapper is not a
 * detail: it is what guarantees the ISO-8601 instants and enum spellings on the wire match what the
 * Python and TypeScript sides expect. Type headers are switched off - the schema is the contract,
 * not a Java class name embedded in the message.
 *
 * <p><strong>Error handling.</strong> A {@link DefaultErrorHandler} retries with exponential
 * backoff, then hands the record to a recoverer that writes a {@code DeadLetterEnvelope} to
 * {@code pdei.dlq.v1}. Failures that cannot succeed on a retry - a malformed body, an unknown event
 * type, an unmappable source event, a monetary value that cannot be represented - are registered as
 * non-retryable and go to the DLQ on the first exception, because retrying them only delays the
 * partition.
 *
 * <p><strong>Offsets.</strong> Manual immediate acknowledgement, auto-commit disabled. The offset
 * moves after the work is durable. {@code DefaultErrorHandler} acknowledges recovered records
 * itself, so a dead-lettered record does not block the partition either.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);
    private static final String SERVICE = "normalization-worker";

    // --- producer -------------------------------------------------------------------------------

    /**
     * Producer for canonical events and dead letters.
     *
     * <p>{@code acks=all} with idempotence enabled: this worker is the sole producer of the
     * canonical topic, and a lost or duplicated write there propagates to every downstream
     * projection. The throughput cost is irrelevant at this scale.
     */
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

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                                   ObjectProvider<MeterRegistry> meterRegistries,
                                                   NormalizationProperties properties) {
        return new DeadLetterPublisher(kafkaTemplate, ConsumerGroups.PDEI_NORMALIZATION_WORKER,
                SERVICE, meterRegistries.getIfAvailable(), properties.getPublishTimeout().toMillis());
    }

    // --- consumer -------------------------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, String> rawEventConsumerFactory(KafkaProperties kafkaProperties,
                                                                   NormalizationProperties properties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, ConsumerGroups.PDEI_NORMALIZATION_WORKER);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getMaxPollRecords());
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> rawEventListenerContainerFactory(
            ConsumerFactory<String, String> rawEventConsumerFactory,
            DefaultErrorHandler normalizationErrorHandler,
            NormalizationProperties properties) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(rawEventConsumerFactory);
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(normalizationErrorHandler);

        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Observation is left off: trace context is propagated explicitly through KafkaTracing so
        // the behaviour is identical with and without an OTel agent, and there is exactly one
        // component responsible for it.
        container.setObservationEnabled(false);
        return factory;
    }

    // --- error handling -------------------------------------------------------------------------

    @Bean
    public DefaultErrorHandler normalizationErrorHandler(DeadLetterPublisher deadLetterPublisher,
                                                         NormalizationProperties properties) {
        NormalizationProperties.Retry retry = properties.getRetry();
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(Math.max(0, retry.getMaxAttempts() - 1));
        backOff.setInitialInterval(retry.getInitialInterval().toMillis());
        backOff.setMultiplier(retry.getMultiplier());
        backOff.setMaxInterval(retry.getMaxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> recover(record, exception, deadLetterPublisher), backOff);

        // Deterministic failures: retrying identical bytes produces an identical failure, so they
        // are dead-lettered immediately rather than after four pointless attempts.
        handler.addNotRetryableExceptions(
                ValidationException.class,
                UnknownEventTypeException.class,
                UnmappableEventException.class,
                MonetaryPrecisionException.class);

        handler.setAckAfterHandle(true);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }

    @SuppressWarnings("unchecked")
    private void recover(ConsumerRecord<?, ?> record,
                         Exception exception,
                         DeadLetterPublisher deadLetterPublisher) {
        ConsumerRecord<String, String> typed = (ConsumerRecord<String, String>) record;
        int attempt = attemptOf(typed);
        log.error("recovering {}-{}@{} after failure: {}", record.topic(), record.partition(),
                record.offset(), exception.toString());
        deadLetterPublisher.publish(typed, unwrap(exception), attempt);
    }

    /** Delivery attempt from the {@code pdei-attempt} header; 1 when the header is absent. */
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

    /**
     * Unwraps the container's {@code ListenerExecutionFailedException} so the dead letter records
     * the failure that actually happened rather than the framework's wrapper.
     */
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
