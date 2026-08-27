package com.laserpay.pdei.audit.config;

import com.laserpay.pdei.audit.chain.AuditChainAppender;
import com.laserpay.pdei.audit.chain.ChainVerifier;
import com.laserpay.pdei.audit.consume.AuditIntake;
import com.laserpay.pdei.audit.consume.DeadLetterPublisher;
import com.laserpay.pdei.audit.consume.IdempotencyGuard;
import com.laserpay.pdei.audit.metrics.AuditMetrics;
import com.laserpay.pdei.audit.repository.AuditEventStore;
import com.laserpay.pdei.audit.repository.JdbcAuditEventStore;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.util.RedisLocks;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the audit service.
 *
 * <p>{@link JdbcAuditEventStore} is registered as the {@link AuditEventStore}, and because that
 * interface extends {@code evidence-core}'s {@code AuditRepositoryPort}, it also takes over that
 * role - which makes {@code CorePersistenceAutoConfiguration} back off from registering its own
 * adapter (every bean there is {@code @ConditionalOnMissingBean}). That is the intended arrangement:
 * the service that owns the audit table owns the code that writes it. It is also necessary, because
 * the shared adapter's SQL does not match {@code V8__audit.sql} - see "Known gaps" in this module's
 * {@code context.md}.
 */
@Configuration(proxyBeanMethods = false)
public class AuditServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceConfig.class);

    @Bean
    public AuditEventStore auditEventStore(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAuditEventStore(jdbc);
    }

    @Bean
    public AuditMetrics auditMetrics(MeterRegistry meterRegistry) {
        return new AuditMetrics(meterRegistry);
    }

    @Bean
    public AuditChainAppender auditChainAppender(AuditEventStore store, RedisLocks locks,
                                                 AuditProperties properties, AuditMetrics metrics) {
        return new AuditChainAppender(store, locks, properties, metrics);
    }

    @Bean
    public ChainVerifier chainVerifier(AuditEventStore store, AuditProperties properties, Clocks clock) {
        return new ChainVerifier(store, properties, clock);
    }

    @Bean
    public IdempotencyGuard idempotencyGuard(ProcessedEventRepository processedEvents,
                                             ObjectProvider<StringRedisTemplate> redisTemplates,
                                             AuditProperties properties, AuditMetrics metrics) {
        return new IdempotencyGuard(processedEvents, redisTemplates.getIfAvailable(),
                ConsumerGroups.PDEI_AUDIT_SERVICE, properties.getIdempotencyTtl(), metrics);
    }

    @Bean
    public AuditIntake auditIntake(IdempotencyGuard idempotency, AuditChainAppender appender,
                                   AuditMetrics metrics) {
        return new AuditIntake(idempotency, appender, metrics);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(
            ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates, Clocks clock) {
        return new DeadLetterPublisher(kafkaTemplates.getIfAvailable(), clock);
    }

    /**
     * The domain topics {@code DomainEventConsumer} subscribes to, resolved from
     * {@code pdei.audit.consume.*} and referenced as {@code #{@auditDomainTopics}}.
     *
     * <p>A bean rather than a literal so a targeted replay can narrow the set: during an
     * investigation it is useful to replay one topic without the others racing new entries into the
     * same merchant chains.
     */
    @Bean
    public String[] auditDomainTopics(AuditProperties properties) {
        AuditProperties.Consume consume = properties.getConsume();
        List<String> topics = new ArrayList<>();
        if (consume.isCanonicalEvents()) {
            topics.add(Topics.CANONICAL_EVENTS);
        }
        if (consume.isEvidenceEvents()) {
            topics.add(Topics.EVIDENCE_EVENTS);
        }
        if (consume.isReadinessEvents()) {
            topics.add(Topics.READINESS_EVENTS);
        }
        if (consume.isDisputeEvents()) {
            topics.add(Topics.DISPUTE_EVENTS);
        }
        if (consume.isCaseEvents()) {
            topics.add(Topics.CASE_EVENTS);
        }
        if (topics.isEmpty()) {
            // A Kafka listener cannot subscribe to nothing. Falling back to the canonical topic
            // keeps the service running and makes the misconfiguration loud rather than silent.
            log.warn("every pdei.audit.consume.* domain topic is disabled; falling back to {}",
                    Topics.CANONICAL_EVENTS);
            topics.add(Topics.CANONICAL_EVENTS);
        }
        log.info("audit-service consuming domain topics: {}", topics);
        return topics.toArray(new String[0]);
    }
}
