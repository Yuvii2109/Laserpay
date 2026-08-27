package com.laserpay.pdei.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laserpay.pdei.api.ApiTestFixtures;
import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.dto.TimelineResponse;
import com.laserpay.pdei.api.dto.TransactionResponse;
import com.laserpay.pdei.api.service.TransactionQueryService;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.money.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over {@link TransactionController}.
 *
 * <p>The service layer is mocked: what is under test here is the HTTP contract of section 8.1, not
 * the readiness engine. What matters is that the routes exist with the right verbs, that filters and
 * pagination reach the service intact, that money crosses the wire as minor units, and that a
 * missing transaction is a 404 rather than a 500.</p>
 */
@WebMvcTest(controllers = TransactionController.class)
@EnableConfigurationProperties(ApiProperties.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TransactionQueryService transactions;

    @Test
    @DisplayName("GET /transactions returns the PageResponse envelope with content/page/size/total")
    void searchReturnsPageEnvelope() throws Exception {
        when(transactions.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(PageResponse.of(List.of(transactionResponse()), 0, 25, 1L));

        mvc.perform(get("/api/v1/transactions").param("merchantId", ApiTestFixtures.MERCHANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionId").value(ApiTestFixtures.TRANSACTION_ID))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("money crosses the wire as amountMinor plus currency, never as a decimal")
    void moneyIsMinorUnits() throws Exception {
        when(transactions.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(PageResponse.of(List.of(transactionResponse()), 0, 25, 1L));

        mvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount.amountMinor").value(1_299_900L))
                .andExpect(jsonPath("$.content[0].amount.currency").value("INR"))
                .andExpect(jsonPath("$.content[0].amount.amount").doesNotExist());
    }

    @Test
    @DisplayName("band, from and to reach the service as parsed types")
    void filtersArePassedThrough() throws Exception {
        when(transactions.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(PageResponse.of(List.of(), 0, 25, 0L));

        mvc.perform(get("/api/v1/transactions")
                        .param("merchantId", ApiTestFixtures.MERCHANT_ID)
                        .param("band", "AT_RISK")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-26T00:00:00Z")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).search(
                eq(ApiTestFixtures.MERCHANT_ID),
                eq(ReadinessBand.AT_RISK),
                eq(Instant.parse("2026-08-01T00:00:00Z")),
                eq(Instant.parse("2026-08-26T00:00:00Z")),
                pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("an unknown transaction is 404 with the shared ErrorResponse and a correlation id")
    void unknownTransactionIs404() throws Exception {
        when(transactions.timeline("TX-nope"))
                .thenThrow(new NotFoundException("TRANSACTION", "TX-nope"));

        mvc.perform(get("/api/v1/transactions/TX-nope/timeline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.at").isNotEmpty());
    }

    @Test
    @DisplayName("GET /transactions/{id}/timeline returns the entry list")
    void timelineIsReturned() throws Exception {
        when(transactions.timeline(ApiTestFixtures.TRANSACTION_ID)).thenReturn(
                TimelineResponse.of(ApiTestFixtures.TRANSACTION_ID,
                        List.of(ApiTestFixtures.timelineEntry()), ApiTestFixtures.NOW));

        mvc.perform(get("/api/v1/transactions/{id}/timeline", ApiTestFixtures.TRANSACTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(ApiTestFixtures.TRANSACTION_ID))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.entries[0].eventType").value("ShipmentDelivered"));
    }

    @Test
    @DisplayName("GET /transactions/{id}/readiness serves the snapshot")
    void readinessIsReturned() throws Exception {
        when(transactions.readiness(eq(ApiTestFixtures.TRANSACTION_ID), isNull()))
                .thenReturn(ApiTestFixtures.readiness(88));

        mvc.perform(get("/api/v1/transactions/{id}/readiness", ApiTestFixtures.TRANSACTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(88))
                .andExpect(jsonPath("$.band").value("NEARLY_READY"));
    }

    @Test
    @DisplayName("recompute is a POST and forwards the reason code")
    void recomputeIsAPost() throws Exception {
        when(transactions.recompute(ApiTestFixtures.TRANSACTION_ID,
                DisputeReasonCode.GOODS_NOT_RECEIVED))
                .thenReturn(ApiTestFixtures.readiness(93));

        mvc.perform(post("/api/v1/transactions/{id}/readiness/recompute", ApiTestFixtures.TRANSACTION_ID)
                        .param("reasonCode", "GOODS_NOT_RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(93))
                .andExpect(jsonPath("$.band").value("READY"));

        verify(transactions).recompute(ApiTestFixtures.TRANSACTION_ID,
                DisputeReasonCode.GOODS_NOT_RECEIVED);
    }

    @Test
    @DisplayName("recompute is not reachable by GET: a write must not be a link")
    void recomputeRejectsGet() throws Exception {
        mvc.perform(get("/api/v1/transactions/{id}/readiness/recompute", ApiTestFixtures.TRANSACTION_ID))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("an unparseable enum parameter is 400, not 500")
    void badEnumParameterIs400() throws Exception {
        mvc.perform(get("/api/v1/transactions").param("band", "NOT_A_BAND"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.parameter").value("band"));
    }

    @Test
    @DisplayName("GET /transactions/{id}/evidence returns the artifact list")
    void evidenceIsReturned() throws Exception {
        when(transactions.evidence(ApiTestFixtures.TRANSACTION_ID))
                .thenReturn(List.of(ApiTestFixtures.evidence()));

        mvc.perform(get("/api/v1/transactions/{id}/evidence", ApiTestFixtures.TRANSACTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evidenceId").value(ApiTestFixtures.EVIDENCE_ID))
                .andExpect(jsonPath("$[0].type").value("DELIVERY_PROOF"));
    }

    private static TransactionResponse transactionResponse() {
        return new TransactionResponse(
                ApiTestFixtures.TRANSACTION_ID,
                ApiTestFixtures.MERCHANT_ID,
                "CUS-000001",
                "ext-1",
                ApiTestFixtures.AMOUNT,
                ApiTestFixtures.AMOUNT,
                Money.zero("INR"),
                "CAPTURED",
                "WEB",
                88,
                ReadinessBand.NEARLY_READY,
                ApiTestFixtures.NOW,
                ApiTestFixtures.NOW.minusSeconds(86_400),
                ApiTestFixtures.NOW.minusSeconds(86_390),
                "11111111-1111-1111-1111-111111111111",
                ApiTestFixtures.NOW,
                null);
    }
}
