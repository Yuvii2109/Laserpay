package com.laserpay.pdei.readiness.config;

import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.config.CoreProperties;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.readiness.ContradictionDetector;
import com.laserpay.pdei.core.readiness.GapDetector;
import com.laserpay.pdei.core.readiness.ReadinessDataProvider;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.util.RedisLocks;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import com.laserpay.pdei.readiness.consume.DeadLetterPublisher;
import com.laserpay.pdei.readiness.consume.EventIntake;
import com.laserpay.pdei.readiness.consume.IdempotencyGuard;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import com.laserpay.pdei.readiness.persistence.EvidenceExpiryStore;
import com.laserpay.pdei.readiness.persistence.JdbcEvidenceExpiryStore;
import com.laserpay.pdei.readiness.persistence.JdbcReadinessDataProvider;
import com.laserpay.pdei.readiness.persistence.ReadinessStore;
import com.laserpay.pdei.readiness.persistence.TransactionResolver;
import com.laserpay.pdei.readiness.publish.ReadinessEventPublisher;
import com.laserpay.pdei.readiness.recompute.ReadinessCache;
import com.laserpay.pdei.readiness.recompute.ReadinessRecomputeService;
import com.laserpay.pdei.readiness.recompute.RecomputeDebouncer;
import com.laserpay.pdei.readiness.recompute.RecomputeLock;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the readiness worker.
 *
 * <p>Two deliberate overrides of {@code evidence-core}'s auto-configuration, both legitimate uses of
 * the extension point its bean methods document ("a service module that prefers its own strategy
 * simply declares its own bean for a port"):
 *
 * <ol>
 *   <li>{@link ReadinessStore} is published as the {@code ReadinessRepositoryPort}, because the
 *       worker must write {@code is_current}, {@code trigger_reason} and {@code trigger_event_id},
 *       which the shared port signature cannot express;</li>
 *   <li>{@link JdbcReadinessDataProvider} is published as the {@link ReadinessDataProvider}, so the
 *       engine reads through four narrow queries written against the migration's actual columns.</li>
 * </ol>
 *
 * <p>{@link ReadinessEngine} is also built here rather than being left to auto-configuration, for
 * one reason worth stating plainly: the engine records
 * {@code pdei_readiness_computation_seconds} and {@code pdei_readiness_score{merchant}} only when it
 * is handed a {@link MeterRegistry}. Two of the metrics this service is required to publish
 * (PLATFORM-CONTRACT section 13) therefore depend on that argument being passed, so it is passed
 * explicitly and visibly.
 */
@Configuration(proxyBeanMethods = false)
public class ReadinessWorkerConfig {

    // --- persistence ----------------------------------------------------------------------------

    /**
     * Also satisfies {@code ReadinessRepositoryPort} by type, which is what makes
     * {@code CorePersistenceAutoConfiguration} back off from registering its own adapter.
     */
    @Bean
    public ReadinessStore readinessStore(NamedParameterJdbcTemplate jdbc) {
        return new ReadinessStore(jdbc);
    }

    @Bean
    public EvidenceExpiryStore evidenceExpiryStore(NamedParameterJdbcTemplate jdbc) {
        return new JdbcEvidenceExpiryStore(jdbc);
    }

    @Bean
    public TransactionResolver transactionResolver(NamedParameterJdbcTemplate jdbc) {
        return new TransactionResolver(jdbc);
    }

    @Bean
    public ReadinessDataProvider readinessDataProvider(NamedParameterJdbcTemplate jdbc,
                                                       PolicyEngine policyEngine) {
        return new JdbcReadinessDataProvider(jdbc, policyEngine);
    }

    // --- domain engine --------------------------------------------------------------------------

    @Bean
    public ReadinessEngine readinessEngine(ReadinessDataProvider provider, GapDetector gapDetector,
                                           ContradictionDetector contradictionDetector, Clocks clock,
                                           MeterRegistry meterRegistry) {
        return new ReadinessEngine(provider, gapDetector, contradictionDetector, clock, meterRegistry);
    }

    // --- recomputation pipeline -----------------------------------------------------------------

    @Bean
    public ReadinessWorkerMetrics readinessWorkerMetrics(MeterRegistry meterRegistry) {
        return new ReadinessWorkerMetrics(meterRegistry);
    }

    @Bean
    public ReadinessCache readinessCache(ObjectProvider<StringRedisTemplate> redisTemplates,
                                         CoreProperties coreProperties) {
        return new ReadinessCache(redisTemplates.getIfAvailable(),
                coreProperties.getReadiness().getCacheTtl());
    }

    @Bean
    public RecomputeLock recomputeLock(RedisLocks locks,
                                       ObjectProvider<StringRedisTemplate> redisTemplates) {
        return new RecomputeLock(locks, redisTemplates.getIfAvailable());
    }

    @Bean
    public ReadinessEventPublisher readinessEventPublisher(EventPublisherPort publisher, Clocks clock) {
        return new ReadinessEventPublisher(publisher, clock);
    }

    @Bean
    public ReadinessRecomputeService readinessRecomputeService(ReadinessEngine engine,
                                                               ReadinessStore store,
                                                               ReadinessCache cache,
                                                               ReadinessEventPublisher publisher,
                                                               RecomputeLock lock,
                                                               ReadinessProperties properties,
                                                               ReadinessWorkerMetrics metrics) {
        return new ReadinessRecomputeService(engine, store, cache, publisher, lock, properties, metrics);
    }

    /**
     * {@code destroyMethod = "close"} matters: shutdown flushes every open debounce window so a
     * redeploy cannot silently drop recomputations for events already acknowledged from Kafka.
     */
    @Bean(destroyMethod = "close")
    public RecomputeDebouncer recomputeDebouncer(ReadinessRecomputeService recomputeService,
                                                 Clocks clock, ReadinessProperties properties,
                                                 ReadinessWorkerMetrics metrics) {
        return RecomputeDebouncer.create(recomputeService::recompute, clock, properties, metrics);
    }

    // --- consumption ----------------------------------------------------------------------------

    @Bean
    public IdempotencyGuard idempotencyGuard(ProcessedEventRepository processedEvents,
                                             ObjectProvider<StringRedisTemplate> redisTemplates,
                                             ReadinessProperties properties,
                                             ReadinessWorkerMetrics metrics) {
        return new IdempotencyGuard(processedEvents, redisTemplates.getIfAvailable(),
                ConsumerGroups.PDEI_READINESS_WORKER, properties.getIdempotencyTtl(), metrics);
    }

    @Bean
    public EventIntake eventIntake(IdempotencyGuard idempotency, TransactionResolver resolver,
                                   RecomputeDebouncer debouncer, ReadinessCache cache,
                                   ReadinessWorkerMetrics metrics, Clocks clock) {
        return new EventIntake(idempotency, resolver, debouncer, cache, metrics, clock);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(
            ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates, Clocks clock) {
        return new DeadLetterPublisher(kafkaTemplates.getIfAvailable(), clock);
    }
}
