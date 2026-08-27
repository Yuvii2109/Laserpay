package com.laserpay.pdei.ingestion.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.ingestion.IngestionTestSupport;
import com.laserpay.pdei.ingestion.dedupe.IdempotencyService;
import com.laserpay.pdei.ingestion.publisher.RawEventPublisher;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc coverage of the four behaviours PLATFORM-CONTRACT section 8.2 actually promises: accept,
 * reject on schema violation, suppress duplicates, enforce the batch cap.
 *
 * <p>The schema registry, validator and ingestion pipeline are the real implementations running
 * against the real {@code /schemas/events} files (the module POM copies them onto the test
 * classpath). Only Kafka and the idempotency store are mocked, because a unit test must not need a
 * broker.
 */
@WebMvcTest(controllers = {IngestionController.class, WebhookController.class})
@Import(IngestionTestSupport.class)
class IngestionControllerTest {

    private static final String VALID_PAYMENT_CREATED = """
            {
              "sourceSystem": "psp-adapter",
              "sourceEventType": "PaymentCreated",
              "merchantId": "MER-0001",
              "occurredAt": "2026-08-26T10:15:30.123Z",
              "body": {
                "paymentId": "PAY-000123",
                "transactionId": "TX-000123",
                "customerId": "CUS-000045",
                "amount": { "amountMinor": 1299900, "currency": "INR" },
                "method": "CARD",
                "cardLast4": "4242",
                "cardNetwork": "VISA",
                "createdAt": "2026-08-26T10:15:30.123Z"
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RawEventPublisher publisher;

    @MockBean
    private IdempotencyService idempotency;

    @BeforeEach
    void firstSightingByDefault() {
        when(idempotency.claim(anyString())).thenReturn(IdempotencyService.Decision.FIRST_SEEN);
    }

    @Test
    @DisplayName("accepts a schema-valid event, publishes it, and returns 202 with the assigned id")
    void acceptsValidEvent() throws Exception {
        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IngestionController.IDEMPOTENCY_KEY_HEADER, "psp-evt-1001")
                        .content(VALID_PAYMENT_CREATED))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected", hasSize(0)))
                .andExpect(header().exists(IngestionController.RAW_EVENT_ID_HEADER));

        verify(idempotency).claim("psp-evt-1001");
        verify(publisher).publish(any(RawEventEnvelope.class), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("rejects a schema violation with field-level detail and never publishes it")
    void rejectsSchemaViolation() throws Exception {
        // Money as a bare decimal is the violation this platform cares most about: the schema types
        // `amount` as { amountMinor, currency }, so 12999.00 is a type error, not a rounding problem.
        String floatMoney = """
                {
                  "sourceSystem": "psp-adapter",
                  "sourceEventType": "PaymentCreated",
                  "merchantId": "MER-0001",
                  "body": {
                    "paymentId": "PAY-000123",
                    "transactionId": "TX-000123",
                    "amount": 12999.00
                  }
                }
                """;

        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(floatMoney))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected", hasSize(1)))
                .andExpect(jsonPath("$.rejected[0].index").value(0))
                .andExpect(jsonPath("$.rejected[0].code").value("SCHEMA_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.rejected[0].sourceEventType").value("PaymentCreated"))
                .andExpect(jsonPath("$.rejected[0].errors", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.rejected[0].errors[?(@.field == 'body.amount')]", hasSize(1)))
                .andExpect(jsonPath("$.rejected[0].errors[?(@.field == 'body.createdAt')]", hasSize(1)));

        verifyNoInteractions(publisher);
        verify(idempotency, never()).claim(anyString());
    }

    @Test
    @DisplayName("suppresses a duplicate: counted in `duplicates`, never published")
    void suppressesDuplicate() throws Exception {
        when(idempotency.claim("psp-evt-1001")).thenReturn(IdempotencyService.Decision.DUPLICATE);

        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IngestionController.IDEMPOTENCY_KEY_HEADER, "psp-evt-1001")
                        .content(VALID_PAYMENT_CREATED))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.rejected", hasSize(0)));

        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the same Idempotency-Key twice yields one publication and one duplicate")
    void secondDeliveryOfTheSameFactIsADuplicate() throws Exception {
        when(idempotency.claim("psp-evt-2002"))
                .thenReturn(IdempotencyService.Decision.FIRST_SEEN)
                .thenReturn(IdempotencyService.Decision.DUPLICATE);

        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IngestionController.IDEMPOTENCY_KEY_HEADER, "psp-evt-2002")
                        .content(VALID_PAYMENT_CREATED))
                .andExpect(jsonPath("$.accepted").value(1));

        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IngestionController.IDEMPOTENCY_KEY_HEADER, "psp-evt-2002")
                        .content(VALID_PAYMENT_CREATED))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));

        verify(publisher, times(1)).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a batch over 1000 is a 400, not a silent truncation")
    void enforcesBatchLimit() throws Exception {
        mockMvc.perform(post("/ingest/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchOf(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("1001")))
                .andExpect(jsonPath("$.details.maxSize").value(1000))
                .andExpect(jsonPath("$.correlationId").exists());

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("a batch at exactly the cap is accepted")
    void acceptsBatchAtTheCap() throws Exception {
        mockMvc.perform(post("/ingest/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchOf(1000)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1000))
                .andExpect(jsonPath("$.rejected", hasSize(0)));

        verify(publisher, times(1000)).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a mixed batch publishes the good events and reports the bad ones by index")
    void batchIsPartiallyAccepted() throws Exception {
        String batch = """
                [
                  %s,
                  { "sourceSystem": "psp-adapter", "sourceEventType": "PaymentCreated",
                    "merchantId": "MER-0001", "body": { "paymentId": "PAY-2" } },
                  { "sourceEventType": "PaymentCreated", "merchantId": "MER-0001", "body": {} }
                ]
                """.formatted(VALID_PAYMENT_CREATED);

        mockMvc.perform(post("/ingest/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected", hasSize(2)))
                .andExpect(jsonPath("$.rejected[0].index").value(1))
                .andExpect(jsonPath("$.rejected[1].index").value(2))
                .andExpect(jsonPath("$.rejected[1].errors[?(@.field == 'sourceSystem')]", hasSize(1)));
    }

    @Test
    @DisplayName("a publish failure is reported as a rejection and the idempotency claim is released")
    void publishFailureIsReportedAndClaimReleased() throws Exception {
        org.mockito.Mockito.doThrow(new UpstreamUnavailableException("kafka", "broker down"))
                .when(publisher).publish(any(), any(), any(), any());

        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IngestionController.IDEMPOTENCY_KEY_HEADER, "psp-evt-3003")
                        .content(VALID_PAYMENT_CREATED))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected", hasSize(1)))
                .andExpect(jsonPath("$.rejected[0].code").value("PUBLISH_FAILED"));

        verify(idempotency).release("psp-evt-3003");
    }

    @Test
    @DisplayName("unparseable JSON is a 400 with the platform error shape")
    void malformedJsonIsARequestError() throws Exception {
        mockMvc.perform(post("/ingest/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /schemas lists the registered event schemas")
    void listsSchemas() throws Exception {
        mockMvc.perform(get("/ingest/v1/schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'payment-created')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'raw-event')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.name == 'canonical-event')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.eventType == 'ShipmentDelivered')]", hasSize(1)));
    }

    @Test
    @DisplayName("GET /stats reports the accepted/rejected/deduped counters")
    void reportsStats() throws Exception {
        when(idempotency.claim(anyString())).thenReturn(IdempotencyService.Decision.FIRST_SEEN);
        mockMvc.perform(post("/ingest/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYMENT_CREATED));

        mockMvc.perform(get("/ingest/v1/stats"))
                .andExpect(status().isOk())
                // Counters are process-lifetime and this context is shared across the class, so the
                // assertion is "at least the one this test submitted", not an absolute value.
                .andExpect(jsonPath("$.accepted", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.registeredSchemas", greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$.since").exists())
                .andExpect(jsonPath("$.at").exists());
    }

    private static String batchOf(int size) {
        return IntStream.range(0, size)
                .mapToObj(i -> """
                        { "sourceSystem": "psp-adapter", "sourceEventType": "PaymentCaptured",
                          "merchantId": "MER-0001",
                          "body": { "paymentId": "PAY-%06d", "transactionId": "TX-%06d",
                                    "capturedAmount": { "amountMinor": 1000, "currency": "INR" },
                                    "capturedAt": "2026-08-26T10:15:30Z" } }
                        """.formatted(i, i))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
