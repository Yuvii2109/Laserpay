package com.laserpay.pdei.ingestion.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laserpay.pdei.ingestion.IngestionTestSupport;
import com.laserpay.pdei.ingestion.dedupe.IdempotencyService;
import com.laserpay.pdei.ingestion.publisher.RawEventPublisher;
import com.laserpay.pdei.ingestion.security.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /ingest/v1/events/{sourceSystem}/webhook} - the unauthenticated door, and therefore
 * the one whose tests matter most.
 *
 * <p>Covers: a correctly signed delivery is ingested; a wrong signature is 401 and never reaches
 * the pipeline; a replayed (stale) timestamp is 401; an unregistered source is 401.
 */
@WebMvcTest(controllers = {IngestionController.class, WebhookController.class})
@Import(IngestionTestSupport.class)
@TestPropertySource(properties = {
        "ingestion.webhook.signature-verification-enabled=true",
        "ingestion.webhook.secrets.psp-adapter=test-secret",
        "ingestion.webhook.tolerance=5m"
})
class WebhookControllerTest {

    private static final byte[] PAYMENT_CAPTURED = """
            {"eventType":"PaymentCaptured","merchantId":"MER-0001","id":"evt_991",
             "paymentId":"PAY-000123","transactionId":"TX-000123",
             "capturedAmount":{"amountMinor":1299900,"currency":"INR"},
             "settlementReference":"SETL-9912",
             "capturedAt":"2026-08-26T10:15:30.123Z"}
            """.getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookSignatureVerifier verifier;

    @MockBean
    private RawEventPublisher publisher;

    @MockBean
    private IdempotencyService idempotency;

    @BeforeEach
    void firstSightingByDefault() {
        when(idempotency.claim(anyString())).thenReturn(IdempotencyService.Decision.FIRST_SEEN);
    }

    @Test
    @DisplayName("a correctly signed delivery is validated, deduplicated and published")
    void acceptsSignedWebhook() throws Exception {
        String timestamp = IngestionTestSupport.FIXED_NOW.toString();

        mockMvc.perform(post("/ingest/v1/events/psp-adapter/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PDEI-Timestamp", timestamp)
                        .header("X-PDEI-Signature", verifier.sign("psp-adapter", PAYMENT_CAPTURED, timestamp))
                        .content(PAYMENT_CAPTURED))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(0));

        // The source's own event id becomes the idempotency key, so a redelivery collapses.
        verify(idempotency).claim("evt_991");
        verify(publisher).publish(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a wrong signature is 401 and never reaches the pipeline")
    void rejectsWrongSignature() throws Exception {
        mockMvc.perform(post("/ingest/v1/events/psp-adapter/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PDEI-Signature", "sha256=" + "0".repeat(64))
                        .content(PAYMENT_CAPTURED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));

        verify(publisher, never()).publish(any(), any(), any(), any());
        verify(idempotency, never()).claim(anyString());
    }

    @Test
    @DisplayName("a missing signature header is 401")
    void rejectsMissingSignature() throws Exception {
        mockMvc.perform(post("/ingest/v1/events/psp-adapter/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_CAPTURED))
                .andExpect(status().isUnauthorized());

        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a replayed delivery outside the tolerance window is 401 even with a valid MAC")
    void rejectsStaleTimestamp() throws Exception {
        String stale = IngestionTestSupport.FIXED_NOW.minus(Duration.ofHours(2)).toString();

        mockMvc.perform(post("/ingest/v1/events/psp-adapter/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PDEI-Timestamp", stale)
                        .header("X-PDEI-Signature", verifier.sign("psp-adapter", PAYMENT_CAPTURED, stale))
                        .content(PAYMENT_CAPTURED))
                .andExpect(status().isUnauthorized());

        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a source with no registered secret is 401, not a 500")
    void rejectsUnknownSource() throws Exception {
        mockMvc.perform(post("/ingest/v1/events/unregistered-source/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PDEI-Signature", "sha256=" + "a".repeat(64))
                        .content(PAYMENT_CAPTURED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details.sourceSystem").value("unregistered-source"));
    }
}
