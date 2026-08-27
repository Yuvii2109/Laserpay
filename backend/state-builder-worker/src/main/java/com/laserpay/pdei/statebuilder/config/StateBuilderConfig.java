package com.laserpay.pdei.statebuilder.config;

import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.persistence.repository.CommunicationRepository;
import com.laserpay.pdei.persistence.repository.CustomerRepository;
import com.laserpay.pdei.persistence.repository.DeliveryRepository;
import com.laserpay.pdei.persistence.repository.DisputeRepository;
import com.laserpay.pdei.persistence.repository.MerchantRepository;
import com.laserpay.pdei.persistence.repository.OrderLineRepository;
import com.laserpay.pdei.persistence.repository.OrderRepository;
import com.laserpay.pdei.persistence.repository.PaymentRepository;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import com.laserpay.pdei.persistence.repository.RefundRepository;
import com.laserpay.pdei.persistence.repository.ShipmentRepository;
import com.laserpay.pdei.persistence.repository.TransactionRepository;
import com.laserpay.pdei.statebuilder.StateBuilderDispatcher;
import com.laserpay.pdei.statebuilder.StateBuilderService;
import com.laserpay.pdei.statebuilder.evidence.DerivedEvidenceService;
import com.laserpay.pdei.statebuilder.forward.EventForwarder;
import com.laserpay.pdei.statebuilder.handler.AggregateEventHandler;
import com.laserpay.pdei.statebuilder.handler.CommunicationEventHandler;
import com.laserpay.pdei.statebuilder.handler.DisputeEventHandler;
import com.laserpay.pdei.statebuilder.handler.EvidenceEventHandler;
import com.laserpay.pdei.statebuilder.handler.OrderEventHandler;
import com.laserpay.pdei.statebuilder.handler.PaymentEventHandler;
import com.laserpay.pdei.statebuilder.handler.RefundEventHandler;
import com.laserpay.pdei.statebuilder.handler.ShipmentEventHandler;
import com.laserpay.pdei.statebuilder.projection.ReferenceData;
import com.laserpay.pdei.statebuilder.projection.TransactionProjection;
import com.laserpay.pdei.statebuilder.support.IdempotencyGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

/**
 * Wires the projection pipeline.
 *
 * <p>Handlers are ordinary beans collected into a list, so adding an aggregate is a new
 * {@code @Bean} and nothing else - {@link StateBuilderDispatcher} indexes whatever it is given.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StateBuilderProperties.class)
public class StateBuilderConfig {

    private static final Logger log = LoggerFactory.getLogger(StateBuilderConfig.class);

    // --- projections ----------------------------------------------------------------------------

    @Bean
    public ReferenceData referenceData(MerchantRepository merchants,
                                       CustomerRepository customers,
                                       OrderRepository orders,
                                       ShipmentRepository shipments,
                                       PaymentRepository payments,
                                       StateBuilderProperties properties) {
        return new ReferenceData(merchants, customers, orders, shipments, payments,
                properties.getDefaultCurrency());
    }

    @Bean
    public TransactionProjection transactionProjection(TransactionRepository transactions,
                                                       PaymentRepository payments,
                                                       RefundRepository refunds,
                                                       ReferenceData referenceData,
                                                       StateBuilderProperties properties) {
        return new TransactionProjection(transactions, payments, refunds, referenceData,
                properties.getDefaultCurrency());
    }

    // --- evidence and fan-out -------------------------------------------------------------------

    /**
     * {@code EvidenceService} comes from {@code evidence-core} and is itself conditional on an
     * object store being available. It is injected through an {@link ObjectProvider} so a MinIO
     * outage degrades evidence derivation to a warning instead of preventing the worker from
     * projecting financial state at all.
     */
    @Bean
    public DerivedEvidenceService derivedEvidenceService(ObjectProvider<EvidenceService> evidenceServices,
                                                         StateBuilderProperties properties) {
        EvidenceService evidenceService = properties.isDeriveEvidence()
                ? evidenceServices.getIfAvailable()
                : null;
        if (properties.isDeriveEvidence() && evidenceService == null) {
            log.warn("evidence derivation is enabled but no EvidenceService bean is available "
                    + "(object store unreachable?). Projections will still be built.");
        }
        return new DerivedEvidenceService(evidenceService);
    }

    @Bean
    public EventForwarder eventForwarder(KafkaTemplate<String, Object> kafkaTemplate,
                                         StateBuilderProperties properties) {
        return new EventForwarder(kafkaTemplate, properties.getPublishTimeout());
    }

    // --- handlers -------------------------------------------------------------------------------

    @Bean
    public PaymentEventHandler paymentEventHandler(PaymentRepository payments,
                                                   TransactionProjection transactionProjection,
                                                   DerivedEvidenceService derivedEvidence) {
        return new PaymentEventHandler(payments, transactionProjection, derivedEvidence);
    }

    @Bean
    public OrderEventHandler orderEventHandler(OrderRepository orders,
                                               OrderLineRepository orderLines,
                                               TransactionProjection transactionProjection,
                                               DerivedEvidenceService derivedEvidence,
                                               StateBuilderProperties properties) {
        return new OrderEventHandler(orders, orderLines, transactionProjection, derivedEvidence,
                properties.getDefaultCurrency());
    }

    @Bean
    public ShipmentEventHandler shipmentEventHandler(ShipmentRepository shipments,
                                                     DeliveryRepository deliveries,
                                                     ReferenceData referenceData,
                                                     DerivedEvidenceService derivedEvidence,
                                                     StateBuilderProperties properties) {
        return new ShipmentEventHandler(shipments, deliveries, referenceData, derivedEvidence,
                properties.getDefaultCurrency());
    }

    @Bean
    public RefundEventHandler refundEventHandler(RefundRepository refunds,
                                                 TransactionProjection transactionProjection,
                                                 ReferenceData referenceData,
                                                 DerivedEvidenceService derivedEvidence) {
        return new RefundEventHandler(refunds, transactionProjection, referenceData, derivedEvidence);
    }

    @Bean
    public CommunicationEventHandler communicationEventHandler(CommunicationRepository communications,
                                                               TransactionProjection transactionProjection,
                                                               ReferenceData referenceData,
                                                               DerivedEvidenceService derivedEvidence) {
        return new CommunicationEventHandler(communications, transactionProjection, referenceData,
                derivedEvidence);
    }

    @Bean
    public DisputeEventHandler disputeEventHandler(DisputeRepository disputes,
                                                   TransactionProjection transactionProjection,
                                                   ReferenceData referenceData,
                                                   EventForwarder forwarder) {
        return new DisputeEventHandler(disputes, transactionProjection, referenceData, forwarder);
    }

    @Bean
    public EvidenceEventHandler evidenceEventHandler(EventForwarder forwarder) {
        return new EvidenceEventHandler(forwarder);
    }

    // --- pipeline -------------------------------------------------------------------------------

    @Bean
    public StateBuilderDispatcher stateBuilderDispatcher(List<AggregateEventHandler> handlers) {
        return new StateBuilderDispatcher(handlers);
    }

    @Bean
    public IdempotencyGuard idempotencyGuard(ProcessedEventRepository processedEvents,
                                             ObjectProvider<StringRedisTemplate> redisTemplates,
                                             StateBuilderProperties properties) {
        StringRedisTemplate redis = properties.getIdempotency().isRedisEnabled()
                ? redisTemplates.getIfAvailable()
                : null;
        return new IdempotencyGuard(processedEvents, redis, ConsumerGroups.PDEI_STATE_BUILDER_WORKER,
                properties.getIdempotency().getTtl());
    }

    @Bean
    public StateBuilderService stateBuilderService(StateBuilderDispatcher dispatcher,
                                                   IdempotencyGuard idempotencyGuard,
                                                   ObjectProvider<MeterRegistry> meterRegistries) {
        return new StateBuilderService(dispatcher, idempotencyGuard, meterRegistries.getIfAvailable());
    }
}
