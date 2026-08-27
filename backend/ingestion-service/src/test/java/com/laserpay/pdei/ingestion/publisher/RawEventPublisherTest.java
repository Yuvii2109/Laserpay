package com.laserpay.pdei.ingestion.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * What lands on {@code pdei.raw.events.v1}: the right topic, the contract's partition key, the
 * contract's headers - and, when the send fails, a dead letter rather than a lost fact.
 */
class RawEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:15:30Z");

    private KafkaTemplate<String, Object> kafkaTemplate;
    private RawEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new RawEventPublisher(kafkaTemplate, new IngestionProperties(), Clocks.fixed(NOW));
    }

    private RawEventEnvelope envelope() {
        return new RawEventEnvelope(
                "6f0f6e0d-0000-4000-8000-000000000001",
                "psp-adapter",
                "PaymentCaptured",
                "MER-0001",
                NOW,
                "psp-evt-1001",
                Map.of("pdei-source-system", "psp-adapter"),
                Json.readTree("{\"paymentId\":\"PAY-000123\"}"));
    }

    @Test
    @DisplayName("keys the record merchantId:aggregateId and sets every contract header")
    @SuppressWarnings("unchecked")
    void keysAndHeadersFollowTheContract() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(envelope(), "PAY-000123", "corr-9", "00-abc-def-01");

        // Raw captor deliberately: ArgumentCaptor.forClass cannot express ProducerRecord<String,Object>
        // without an unchecked assignment either way, and this form is unambiguous to the compiler.
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> record = captor.getValue();

        assertThat(record.topic()).isEqualTo(Topics.RAW_EVENTS);
        assertThat(record.key()).isEqualTo("MER-0001:PAY-000123");
        assertThat(header(record, EventHeaders.EVENT_ID)).isEqualTo("6f0f6e0d-0000-4000-8000-000000000001");
        assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("PaymentCaptured");
        assertThat(header(record, EventHeaders.MERCHANT_ID)).isEqualTo("MER-0001");
        assertThat(header(record, EventHeaders.CORRELATION_ID)).isEqualTo("corr-9");
        assertThat(header(record, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
        assertThat(header(record, EventHeaders.ATTEMPT)).isEqualTo("1");
        assertThat(header(record, EventHeaders.TRACEPARENT)).isEqualTo("00-abc-def-01");
    }

    @Test
    @DisplayName("with no aggregate id, the merchant-scoped envelope key is used - never an unkeyed record")
    void fallsBackToTheEnvelopeKey() {
        assertThat(RawEventPublisher.partitionKey(envelope(), null))
                .isEqualTo("MER-0001:psp-evt-1001");
        assertThat(RawEventPublisher.partitionKey(envelope(), "  "))
                .isEqualTo("MER-0001:psp-evt-1001");
    }

    @Test
    @DisplayName("a failed send dead-letters the whole envelope and reports the failure upstream")
    @SuppressWarnings("unchecked")
    void deadLettersOnFailure() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> publisher.publish(envelope(), "PAY-000123", "corr-9", null))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining(Topics.RAW_EVENTS);

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(Topics.DLQ),
                org.mockito.ArgumentMatchers.eq("MER-0001:PAY-000123"), any());
    }

    private static String header(ProducerRecord<String, Object> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
