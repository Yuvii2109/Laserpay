package com.laserpay.pdei.normalization;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.normalization.adapter.CrmAdapter;
import com.laserpay.pdei.normalization.adapter.LogisticsAdapter;
import com.laserpay.pdei.normalization.adapter.MerchantPortalAdapter;
import com.laserpay.pdei.normalization.adapter.OrderSystemAdapter;
import com.laserpay.pdei.normalization.adapter.PspAdapter;
import com.laserpay.pdei.normalization.adapter.SimulatorAdapter;
import com.laserpay.pdei.normalization.adapter.SourceAdapterRegistry;
import com.laserpay.pdei.normalization.adapter.UnmappableEventException;
import com.laserpay.pdei.normalization.config.NormalizationProperties;
import com.laserpay.pdei.normalization.support.IdempotencyGuard;
import com.laserpay.pdei.normalization.upcast.LegacyMinorUnitsUpcaster;
import com.laserpay.pdei.normalization.upcast.RetiredSourceEventTypeUpcaster;
import com.laserpay.pdei.normalization.upcast.UpcasterChain;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the normalization pipeline: duplicate suppression, deterministic ids, preserved
 * lateness, and the refusal to guess when a source event cannot be mapped.
 */
class NormalizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:15:31.004Z");

    private static final String CAPTURE_BODY = """
            { "id": "pi_9f2c", "amount": 1299900, "currency": "inr",
              "captured_at": "2026-08-26T09:00:00Z",
              "metadata": { "transaction_id": "TX-82918" } }
            """;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final Set<String> claimed = new HashSet<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private NormalizationService service;

    @BeforeEach
    void setUp() {
        claimed.clear();
        // Stands in for INSERT ... ON CONFLICT DO NOTHING: first caller wins, everyone else sees false.
        when(processedEvents.markProcessed(anyString(), anyString()))
                .thenAnswer(invocation -> claimed.add(
                        invocation.getArgument(0) + "@" + invocation.getArgument(1)));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(
                new PspAdapter("INR"), new OrderSystemAdapter("INR"), new LogisticsAdapter("INR"),
                new CrmAdapter("INR"), new SimulatorAdapter("INR"), new MerchantPortalAdapter("INR")));
        UpcasterChain chain = new UpcasterChain(List.of(
                new LegacyMinorUnitsUpcaster("INR"), new RetiredSourceEventTypeUpcaster()));
        IdempotencyGuard guard = new IdempotencyGuard(processedEvents, null,
                ConsumerGroups.PDEI_NORMALIZATION_WORKER, null);

        service = new NormalizationService(registry, chain, guard, kafkaTemplate,
                Clocks.fixed(NOW), meterRegistry, new NormalizationProperties());
    }

    @Test
    @DisplayName("publishes a canonical event keyed merchantId:aggregateId with the contract headers")
    void publishesCanonicalEvent() {
        CanonicalEvent event = service.normalizeAndPublish(
                RawEvents.of("raw-1", "PSP", "payment_intent.succeeded", CAPTURE_BODY, Map.of()));

        assertThat(event).isNotNull();
        assertThat(event.eventType()).isEqualTo(EventType.PaymentCaptured);

        ArgumentCaptor<ProducerRecord<String, Object>> captor = producerRecordCaptor();
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> record = captor.getValue();

        assertThat(record.topic()).isEqualTo(Topics.CANONICAL_EVENTS);
        assertThat(record.key()).isEqualTo("MER-0001:PAY-pi_9f2c");
        assertThat(record.value()).isInstanceOf(CanonicalEvent.class);
        assertThat(headerOf(record, EventHeaders.EVENT_TYPE)).isEqualTo("PaymentCaptured");
        assertThat(headerOf(record, EventHeaders.MERCHANT_ID)).isEqualTo("MER-0001");
        assertThat(headerOf(record, EventHeaders.EVENT_ID)).isEqualTo(event.eventId());
        assertThat(headerOf(record, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
    }

    @Test
    @DisplayName("duplicate delivery: the second attempt is suppressed and publishes nothing")
    void suppressesDuplicateDelivery() {
        RawEventEnvelope raw = RawEvents.of("raw-dup", "PSP", "payment_intent.succeeded",
                CAPTURE_BODY, Map.of());

        CanonicalEvent first = service.normalizeAndPublish(raw);
        CanonicalEvent second = service.normalizeAndPublish(raw);

        assertThat(first).isNotNull();
        assertThat(second).isNull();
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        assertThat(meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL,
                MetricNames.Tag.SERVICE, "normalization-worker").count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("replay produces an identical eventId, so downstream dedupe collapses it")
    void replayIsDeterministic() {
        String eventId = service.normalizeAndPublish(
                        RawEvents.of("raw-replay", "PSP", "payment_intent.succeeded", CAPTURE_BODY,
                                Map.of()))
                .eventId();

        // Simulates a deliberate replay after a consumer-group offset reset.
        claimed.clear();
        String replayed = service.normalizeAndPublish(
                        RawEvents.of("raw-replay", "PSP", "payment_intent.succeeded", CAPTURE_BODY,
                                Map.of()))
                .eventId();

        assertThat(replayed).isEqualTo(eventId);
    }

    @Test
    @DisplayName("occurredAt comes from the source, observedAt from the clock")
    void preservesLateness() {
        CanonicalEvent event = service.normalizeAndPublish(
                RawEvents.of("raw-late", "PSP", "payment_intent.succeeded", CAPTURE_BODY, Map.of()));

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        assertThat(event.observedAt()).isEqualTo(NOW);
        assertThat(event.ingestionLagMillis()).isEqualTo(4_531_004L);
    }

    @Test
    @DisplayName("an unmappable source system fails loudly so the listener can dead-letter it")
    void refusesUnknownSourceSystem() {
        RawEventEnvelope raw = RawEvents.of("raw-unknown", "mystery-erp", "thing.happened", "{}",
                Map.of());

        assertThatThrownBy(() -> service.normalizeAndPublish(raw))
                .isInstanceOf(UnmappableEventException.class);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("the upcaster chain runs before adapter lookup, so legacy payloads still map")
    void upcastsBeforeMapping() {
        CanonicalEvent event = service.normalizeAndPublish(RawEvents.of("raw-legacy", "PSP",
                "charge.succeeded", """
                        { "id": "ch_legacy", "amount_cents": 250000, "currency": "inr",
                          "captured_at": "2026-08-26T09:00:00Z" }
                        """, Map.of()));

        assertThat(event.eventType()).isEqualTo(EventType.PaymentCaptured);
        assertThat(event.payload().path("capturedAmount").path("amountMinor").asLong())
                .isEqualTo(250_000L);
    }

    @Test
    @DisplayName("a broker failure surfaces, so the idempotency claim rolls back with the transaction")
    void propagatesPublishFailure() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> service.normalizeAndPublish(
                RawEvents.of("raw-fail", "PSP", "payment_intent.succeeded", CAPTURE_BODY, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to publish");
    }

    // --- helpers ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<ProducerRecord<String, Object>> producerRecordCaptor() {
        return ArgumentCaptor.forClass(
                (Class<ProducerRecord<String, Object>>) (Class<?>) ProducerRecord.class);
    }

    private static String headerOf(ProducerRecord<String, Object> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
