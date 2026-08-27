package com.laserpay.pdei.docproc.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring for the evidence listener.
 *
 * <p>Deliberate choices:
 * <ul>
 *   <li><strong>String values, not a typed deserializer.</strong> A record that fails to
 *       deserialise inside the container is invisible to the handler and cannot be routed to
 *       {@code pdei.dlq.v1} with its original bytes. Deserialising in the handler puts the
 *       malformed-envelope case on the same code path as every other failure.</li>
 *   <li><strong>Manual immediate acknowledgement.</strong> The offset commits after the handler's
 *       transaction returns, so a crash mid-extraction redelivers rather than silently skipping
 *       a document.</li>
 *   <li><strong>Short in-container retry.</strong> Two quick attempts absorb a transient MinIO or
 *       Postgres blip; anything more persistent is the handler's problem and is already
 *       dead-lettered with its coordinates. Long retry loops in a consumer stall the partition
 *       behind them.</li>
 *   <li><strong>Bounded poll batch.</strong> Extraction is CPU and memory heavy; fetching 500
 *       records at once just means holding 500 keys the container cannot process within
 *       {@code max.poll.interval.ms}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(KafkaTemplate.class)
@EnableKafka
public class DocProcKafkaConfiguration {

    private static final int MAX_POLL_RECORDS = 20;
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long RETRY_ATTEMPTS = 2L;

    @Bean
    public ConsumerFactory<String, String> docprocConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> docprocKafkaListenerContainerFactory(
            ConsumerFactory<String, String> docprocConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(docprocConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(2);
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS)));
        return factory;
    }
}
