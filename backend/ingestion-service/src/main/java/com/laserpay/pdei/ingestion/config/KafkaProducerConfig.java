package com.laserpay.pdei.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.common.kafka.Topics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * The Kafka producer for {@code pdei.raw.events.v1} and {@code pdei.dlq.v1}.
 *
 * <p><strong>Durability settings are not defaults, they are requirements.</strong>
 * {@code acks=all} plus {@code enable.idempotence=true} is what makes the HTTP 202 honest: the
 * record is on every in-sync replica before the caller is told it was accepted, and a producer-side
 * retry cannot silently write it twice. A payment capture that a broker leader election quietly
 * discards is a fact the merchant permanently loses; on a single-broker dev cluster
 * {@code acks=all} degenerates to {@code acks=1}, but the setting has to be right for the
 * deployment that is not a laptop.
 *
 * <p><strong>Key serializer is {@link StringSerializer}</strong> because the partition key is
 * always the contract's {@code merchantId + ":" + aggregateId} string.
 *
 * <p><strong>Value serializer is {@link JsonSerializer} with type headers switched off.</strong>
 * The {@code __TypeId__} header would pin the consumer to this module's Java class name; downstream
 * deserialises into {@code RawEventEnvelope} explicitly, and the Python and TypeScript sides read
 * the same JSON without knowing Java exists.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfig {

    /**
     * Producer configuration, assembled from {@code spring.kafka.*} plus the durability settings
     * this service requires. Explicit map assembly (rather than a Boot helper) keeps every property
     * that matters visible in one place.
     */
    @Bean
    public ProducerFactory<String, Object> rawEventProducerFactory(KafkaProperties kafkaProperties,
                                                                   ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());

        // Anything the operator set under spring.kafka.properties.* / spring.kafka.producer.properties.*
        config.putAll(kafkaProperties.getProperties());
        config.putAll(kafkaProperties.getProducer().getProperties());

        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        // Bounded so a broker outage surfaces as a rejected event within the HTTP timeout rather
        // than blocking the request thread until the client's default two minutes elapse.
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        // gzip rather than snappy/lz4: pure JDK, no native library to fail to load on some base
        // image. Raw event bodies are small JSON, so the codec choice is not the bottleneck.
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "pdei-ingestion-service");

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config);
        factory.setValueSerializer(new JsonSerializer<>(objectMapper).noTypeInfo());
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> rawEventProducerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(rawEventProducerFactory);
        template.setDefaultTopic(Topics.RAW_EVENTS);
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * Declares the produced topics so a fresh dev cluster works without a manual
     * {@code kafka-topics --create}. Partition counts come from {@code Topics}, which holds the
     * contract's numbers - ordering guarantees depend on them, so they are not a local choice.
     *
     * <p>{@code KafkaAdmin} applies these on startup and logs (rather than fails) when the broker is
     * absent, so ingestion still starts in a Kafka-less test environment.
     */
    @Bean
    @ConditionalOnProperty(name = "ingestion.publisher.create-topics", havingValue = "true",
            matchIfMissing = true)
    public org.apache.kafka.clients.admin.NewTopic rawEventsTopic() {
        return TopicBuilder.name(Topics.RAW_EVENTS)
                .partitions(Topics.partitions(Topics.RAW_EVENTS))
                .replicas(Topics.DEV_REPLICATION_FACTOR)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "ingestion.publisher.create-topics", havingValue = "true",
            matchIfMissing = true)
    public org.apache.kafka.clients.admin.NewTopic deadLetterTopic() {
        return TopicBuilder.name(Topics.DLQ)
                .partitions(Topics.partitions(Topics.DLQ))
                .replicas(Topics.DEV_REPLICATION_FACTOR)
                .build();
    }
}
