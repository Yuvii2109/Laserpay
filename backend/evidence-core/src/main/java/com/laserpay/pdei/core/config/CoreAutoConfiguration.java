package com.laserpay.pdei.core.config;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.ai.AdmissionController;
import com.laserpay.pdei.core.ai.AiBudgetGate;
import com.laserpay.pdei.core.ai.AiReasoningClient;
import com.laserpay.pdei.core.ai.CircuitBreaker;
import com.laserpay.pdei.core.ai.DeterministicInvestigator;
import com.laserpay.pdei.core.ai.HttpAiReasoningClient;
import com.laserpay.pdei.core.ai.RedisAiBudgetGate;
import com.laserpay.pdei.core.audit.AuditRecorder;
import com.laserpay.pdei.core.dispute.CaseAssemblyService;
import com.laserpay.pdei.core.dispute.DisputeService;
import com.laserpay.pdei.core.evidence.EvidenceGraphService;
import com.laserpay.pdei.core.evidence.EvidenceIntegrityService;
import com.laserpay.pdei.core.evidence.EvidenceLineageService;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.core.policy.PolicyEngine;
import com.laserpay.pdei.core.policy.PolicyVersionService;
import com.laserpay.pdei.core.readiness.ContradictionDetector;
import com.laserpay.pdei.core.readiness.DefaultReadinessDataProvider;
import com.laserpay.pdei.core.readiness.GapDetector;
import com.laserpay.pdei.core.readiness.ReadinessDataProvider;
import com.laserpay.pdei.core.readiness.ReadinessEngine;
import com.laserpay.pdei.core.safety.AiResultValidator;
import com.laserpay.pdei.core.safety.SafetyGate;
import com.laserpay.pdei.core.search.EvidenceSearchService;
import com.laserpay.pdei.core.spi.AuditRepositoryPort;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.EventPublisherPort;
import com.laserpay.pdei.core.spi.EvidenceRepositoryPort;
import com.laserpay.pdei.core.spi.NoOpEventPublisher;
import com.laserpay.pdei.core.spi.PolicyRepositoryPort;
import com.laserpay.pdei.core.spi.TransactionRepositoryPort;
import com.laserpay.pdei.core.spi.kafka.KafkaEventPublisher;
import com.laserpay.pdei.core.storage.MinioObjectStore;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.core.timeline.TimelineService;
import com.laserpay.pdei.core.util.RedisLocks;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;


/**
 * Registers the domain engine as beans.
 *
 * <p>evidence-core is a library, not an application: a service module adds the dependency and gets
 * the engine. Every bean is {@code @ConditionalOnMissingBean}, so any service can substitute its own
 * implementation - which is how the workers inject test doubles and how a future module could swap a
 * port for a different persistence strategy.</p>
 *
 * <p>Infrastructure beans degrade rather than fail: no Kafka template means events are logged instead
 * of published, no Redis means the audit lock and the AI budget gate fall back to their documented
 * behaviour, and no DataSource means the JDBC ports are simply not registered (the module still
 * compiles, and pure components such as {@link ReadinessEngine#score} still work).</p>
 */
@AutoConfiguration(after = CorePersistenceAutoConfiguration.class, afterName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "com.laserpay.pdei.persistence.config.PersistenceAutoConfiguration"
})
@EnableConfigurationProperties(CoreProperties.class)
public class CoreAutoConfiguration {

    // --- infrastructure -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public Clocks pdeiClocks() {
        return Clocks.system();
    }

    @Bean
    @ConditionalOnClass(MinioClient.class)
    @ConditionalOnMissingBean
    public MinioClient pdeiMinioClient(CoreProperties properties) {
        CoreProperties.Storage storage = properties.getStorage();
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(storage.getEndpoint())
                .credentials(storage.getAccessKey(), storage.getSecretKey());
        if (storage.getRegion() != null && !storage.getRegion().isBlank()) {
            builder = builder.region(storage.getRegion());
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(MinioClient.class)
    @ConditionalOnMissingBean
    public ObjectStore pdeiObjectStore(MinioClient minioClient, CoreProperties properties) {
        CoreProperties.Storage storage = properties.getStorage();
        return new MinioObjectStore(minioClient, storage.getBuckets(),
                storage.isEnsureBucketsOnStartup(), storage.isVersioningEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisherPort pdeiEventPublisher(
            ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplates) {
        KafkaTemplate<String, Object> template = kafkaTemplates.getIfAvailable();
        return template == null ? new NoOpEventPublisher() : new KafkaEventPublisher(template);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisLocks pdeiRedisLocks(ObjectProvider<StringRedisTemplate> redisTemplates) {
        return new RedisLocks(redisTemplates.getIfAvailable());
    }

    // --- audit ----------------------------------------------------------------------------------

    @Bean
    @ConditionalOnBean(AuditRepositoryPort.class)
    @ConditionalOnMissingBean
    public AuditRecorder pdeiAuditRecorder(AuditRepositoryPort repository, EventPublisherPort publisher,
                                           RedisLocks locks, Clocks clock, CoreProperties properties) {
        EventPublisherPort effective = properties.getAudit().isPublishToKafka()
                ? publisher : new NoOpEventPublisher();
        return new AuditRecorder(repository, effective, locks, clock);
    }

    // --- policy ---------------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public PolicyEngine pdeiPolicyEngine(ObjectProvider<PolicyRepositoryPort> policies, Clocks clock) {
        return new PolicyEngine(policies.getIfAvailable(), clock);
    }

    @Bean
    @ConditionalOnBean({PolicyRepositoryPort.class, AuditRecorder.class})
    @ConditionalOnMissingBean
    public PolicyVersionService pdeiPolicyVersionService(PolicyRepositoryPort policies,
                                                         AuditRecorder audit, Clocks clock) {
        return new PolicyVersionService(policies, audit, clock);
    }

    // --- readiness ------------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public GapDetector pdeiGapDetector(CoreProperties properties) {
        return new GapDetector(properties.getReadiness().getExpiringSoonDays(),
                properties.getReadiness().getLowQualityThreshold());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContradictionDetector pdeiContradictionDetector() {
        return new ContradictionDetector();
    }

    @Bean
    @ConditionalOnBean({TransactionRepositoryPort.class, EvidenceRepositoryPort.class})
    @ConditionalOnMissingBean
    public ReadinessDataProvider pdeiReadinessDataProvider(TransactionRepositoryPort transactions,
                                                           EvidenceRepositoryPort evidence,
                                                           PolicyEngine policyEngine) {
        return new DefaultReadinessDataProvider(transactions, evidence, policyEngine);
    }

    @Bean
    @ConditionalOnBean(ReadinessDataProvider.class)
    @ConditionalOnMissingBean
    public ReadinessEngine pdeiReadinessEngine(ReadinessDataProvider provider, GapDetector gapDetector,
                                               ContradictionDetector contradictionDetector, Clocks clock,
                                               ObjectProvider<MeterRegistry> meterRegistries) {
        return new ReadinessEngine(provider, gapDetector, contradictionDetector, clock,
                meterRegistries.getIfAvailable());
    }

    // --- evidence -------------------------------------------------------------------------------

    @Bean
    @ConditionalOnBean({EvidenceRepositoryPort.class, ObjectStore.class, AuditRecorder.class})
    @ConditionalOnMissingBean
    public EvidenceService pdeiEvidenceService(EvidenceRepositoryPort repository, ObjectStore objectStore,
                                               EventPublisherPort publisher, AuditRecorder audit,
                                               PolicyEngine policyEngine, Clocks clock,
                                               ObjectProvider<MeterRegistry> meterRegistries,
                                               CoreProperties properties) {
        return new EvidenceService(repository, objectStore, publisher, audit, policyEngine, clock,
                meterRegistries.getIfAvailable(), properties.getStorage().getPresignTtl());
    }

    @Bean
    @ConditionalOnBean(EvidenceService.class)
    @ConditionalOnMissingBean
    public EvidenceIntegrityService pdeiEvidenceIntegrityService(EvidenceRepositoryPort repository,
                                                                 ObjectStore objectStore,
                                                                 EvidenceService evidenceService,
                                                                 AuditRecorder audit, Clocks clock) {
        return new EvidenceIntegrityService(repository, objectStore, evidenceService, audit, clock);
    }

    @Bean
    @ConditionalOnBean({TransactionRepositoryPort.class, EvidenceRepositoryPort.class})
    @ConditionalOnMissingBean
    public EvidenceGraphService pdeiEvidenceGraphService(TransactionRepositoryPort transactions,
                                                         EvidenceRepositoryPort evidence,
                                                         ContradictionDetector contradictionDetector,
                                                         Clocks clock) {
        return new EvidenceGraphService(transactions, evidence, contradictionDetector, clock);
    }

    @Bean
    @ConditionalOnBean(EvidenceRepositoryPort.class)
    @ConditionalOnMissingBean
    public EvidenceLineageService pdeiEvidenceLineageService(EvidenceRepositoryPort repository,
                                                             Clocks clock) {
        return new EvidenceLineageService(repository, clock);
    }

    @Bean
    @ConditionalOnBean(EvidenceRepositoryPort.class)
    @ConditionalOnMissingBean
    public EvidenceSearchService pdeiEvidenceSearchService(EvidenceRepositoryPort repository) {
        return new EvidenceSearchService(repository);
    }

    // --- safety ---------------------------------------------------------------------------------

    @Bean
    @ConditionalOnBean(EvidenceRepositoryPort.class)
    @ConditionalOnMissingBean
    public AiResultValidator pdeiAiResultValidator(EvidenceRepositoryPort evidence,
                                                   ObjectProvider<MeterRegistry> meterRegistries) {
        return new AiResultValidator(evidence, meterRegistries.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean({AiResultValidator.class, AuditRecorder.class})
    @ConditionalOnMissingBean
    public SafetyGate pdeiSafetyGate(AiResultValidator validator, PolicyEngine policyEngine,
                                     AuditRecorder audit, ObjectProvider<MeterRegistry> meterRegistries,
                                     CoreProperties properties) {
        return new SafetyGate(validator, policyEngine, audit, meterRegistries.getIfAvailable(),
                properties.getSafety().getUnattendedConfidence());
    }

    // --- ai -------------------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public DeterministicInvestigator pdeiDeterministicInvestigator() {
        return new DeterministicInvestigator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreaker pdeiAiCircuitBreaker(CoreProperties properties) {
        return new CircuitBreaker(properties.getAi().getCircuitFailureThreshold(),
                properties.getAi().getCircuitOpenDuration());
    }

    @Bean
    @ConditionalOnMissingBean
    public AiBudgetGate pdeiAiBudgetGate(ObjectProvider<StringRedisTemplate> redisTemplates,
                                         CoreProperties properties) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return AiBudgetGate.unlimited();
        }
        CoreProperties.Ai ai = properties.getAi();
        return new RedisAiBudgetGate(redis, ai.getBucketCapacity(), ai.getBucketRefillPerSecond(),
                ai.getDailyBudget());
    }

    @Bean
    @ConditionalOnMissingBean
    public AdmissionController pdeiAdmissionController(AiBudgetGate budgetGate,
                                                       ObjectProvider<CaseRepositoryPort> cases,
                                                       ObjectProvider<MeterRegistry> meterRegistries,
                                                       CoreProperties properties) {
        CoreProperties.Ai ai = properties.getAi();
        return new AdmissionController(budgetGate, cases.getIfAvailable(),
                meterRegistries.getIfAvailable(), ai.getPriorityThreshold(),
                ai.getFinancialImpactCapMinor(), ai.getAmbiguityCap());
    }

    @Bean
    @ConditionalOnMissingBean
    public AiReasoningClient pdeiAiReasoningClient(CircuitBreaker circuitBreaker,
                                                   DeterministicInvestigator fallback,
                                                   ObjectProvider<MeterRegistry> meterRegistries,
                                                   CoreProperties properties) {
        CoreProperties.Ai ai = properties.getAi();
        return new HttpAiReasoningClient(ai.getServiceUrl(), ai.getServiceToken(), ai.getConnectTimeout(),
                ai.getReadTimeout(), ai.getMaxAttempts(), ai.getInitialBackoff(),
                ai.getBackoffMultiplier(), circuitBreaker, fallback, meterRegistries.getIfAvailable());
    }

    // --- disputes and cases ---------------------------------------------------------------------

    @Bean
    @ConditionalOnBean({CaseRepositoryPort.class, AuditRecorder.class})
    @ConditionalOnMissingBean
    public DisputeService pdeiDisputeService(CaseRepositoryPort cases, PolicyEngine policyEngine,
                                             EventPublisherPort publisher, AuditRecorder audit,
                                             Clocks clock) {
        return new DisputeService(cases, policyEngine, publisher, audit, clock);
    }

    @Bean
    @ConditionalOnBean({TransactionRepositoryPort.class, EvidenceRepositoryPort.class,
            CaseRepositoryPort.class})
    @ConditionalOnMissingBean
    public TimelineService pdeiTimelineService(TransactionRepositoryPort transactions,
                                               EvidenceRepositoryPort evidence, CaseRepositoryPort cases) {
        return new TimelineService(transactions, evidence, cases);
    }

    @Bean
    @ConditionalOnBean({CaseRepositoryPort.class, ReadinessEngine.class, EvidenceIntegrityService.class,
            TimelineService.class})
    @ConditionalOnMissingBean
    public CaseAssemblyService pdeiCaseAssemblyService(CaseRepositoryPort cases,
                                                       EvidenceRepositoryPort evidence,
                                                       ObjectStore objectStore,
                                                       ReadinessEngine readinessEngine,
                                                       PolicyEngine policyEngine,
                                                       EvidenceIntegrityService integrityService,
                                                       EvidenceGraphService graphService,
                                                       TimelineService timelineService,
                                                       AuditRecorder audit, EventPublisherPort publisher,
                                                       Clocks clock,
                                                       ObjectProvider<MeterRegistry> meterRegistries) {
        return new CaseAssemblyService(cases, evidence, objectStore, readinessEngine, policyEngine,
                integrityService, graphService, timelineService, audit, publisher, clock,
                meterRegistries.getIfAvailable());
    }

}
