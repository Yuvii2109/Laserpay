package com.laserpay.pdei.normalization.config;

import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.normalization.NormalizationService;
import com.laserpay.pdei.normalization.adapter.CrmAdapter;
import com.laserpay.pdei.normalization.adapter.LogisticsAdapter;
import com.laserpay.pdei.normalization.adapter.MerchantPortalAdapter;
import com.laserpay.pdei.normalization.adapter.OrderSystemAdapter;
import com.laserpay.pdei.normalization.adapter.PspAdapter;
import com.laserpay.pdei.normalization.adapter.SimulatorAdapter;
import com.laserpay.pdei.normalization.adapter.SourceAdapter;
import com.laserpay.pdei.normalization.adapter.SourceAdapterRegistry;
import com.laserpay.pdei.normalization.support.IdempotencyGuard;
import com.laserpay.pdei.normalization.upcast.EventUpcaster;
import com.laserpay.pdei.normalization.upcast.LegacyMinorUnitsUpcaster;
import com.laserpay.pdei.normalization.upcast.RetiredSourceEventTypeUpcaster;
import com.laserpay.pdei.normalization.upcast.UpcasterChain;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

/**
 * Wires the translation pipeline.
 *
 * <p>Adapters and upcasters are ordinary beans collected into lists, so adding a source system or a
 * schema migration is a new {@code @Bean} and nothing else - no registry edit, no switch statement.
 * That is the extension point this module is designed around.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NormalizationProperties.class)
public class NormalizationConfig {

    // --- adapters -------------------------------------------------------------------------------

    @Bean
    public PspAdapter pspAdapter(NormalizationProperties properties) {
        return new PspAdapter(properties.getDefaultCurrency());
    }

    @Bean
    public OrderSystemAdapter orderSystemAdapter(NormalizationProperties properties) {
        return new OrderSystemAdapter(properties.getDefaultCurrency());
    }

    @Bean
    public LogisticsAdapter logisticsAdapter(NormalizationProperties properties) {
        return new LogisticsAdapter(properties.getDefaultCurrency());
    }

    @Bean
    public CrmAdapter crmAdapter(NormalizationProperties properties) {
        return new CrmAdapter(properties.getDefaultCurrency());
    }

    @Bean
    public SimulatorAdapter simulatorAdapter(NormalizationProperties properties) {
        return new SimulatorAdapter(properties.getDefaultCurrency());
    }

    @Bean
    public MerchantPortalAdapter merchantPortalAdapter(NormalizationProperties properties) {
        return new MerchantPortalAdapter(properties.getDefaultCurrency());
    }

    @Bean
    public SourceAdapterRegistry sourceAdapterRegistry(List<SourceAdapter> adapters) {
        return new SourceAdapterRegistry(adapters);
    }

    // --- upcasters ------------------------------------------------------------------------------

    @Bean
    public LegacyMinorUnitsUpcaster legacyMinorUnitsUpcaster(NormalizationProperties properties) {
        return new LegacyMinorUnitsUpcaster(properties.getDefaultCurrency());
    }

    @Bean
    public RetiredSourceEventTypeUpcaster retiredSourceEventTypeUpcaster() {
        return new RetiredSourceEventTypeUpcaster();
    }

    @Bean
    public UpcasterChain upcasterChain(List<EventUpcaster> upcasters) {
        return new UpcasterChain(upcasters);
    }

    // --- pipeline -------------------------------------------------------------------------------

    @Bean
    public Clocks pdeiClocks() {
        return Clocks.system();
    }

    /**
     * Redis is injected optionally: {@code pdei.normalization.idempotency.redis-enabled=false} or a
     * missing template both leave Postgres as the sole (and authoritative) dedupe store.
     */
    @Bean
    public IdempotencyGuard idempotencyGuard(ProcessedEventRepository processedEvents,
                                             ObjectProvider<StringRedisTemplate> redisTemplates,
                                             NormalizationProperties properties) {
        StringRedisTemplate redis = properties.getIdempotency().isRedisEnabled()
                ? redisTemplates.getIfAvailable()
                : null;
        return new IdempotencyGuard(processedEvents, redis, ConsumerGroups.PDEI_NORMALIZATION_WORKER,
                properties.getIdempotency().getTtl());
    }

    @Bean
    public NormalizationService normalizationService(SourceAdapterRegistry registry,
                                                     UpcasterChain upcasterChain,
                                                     IdempotencyGuard idempotencyGuard,
                                                     KafkaTemplate<String, Object> kafkaTemplate,
                                                     Clocks clock,
                                                     ObjectProvider<MeterRegistry> meterRegistries,
                                                     NormalizationProperties properties) {
        return new NormalizationService(registry, upcasterChain, idempotencyGuard, kafkaTemplate,
                clock, meterRegistries.getIfAvailable(), properties);
    }
}
