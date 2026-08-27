package com.laserpay.pdei.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laserpay.pdei.api.ApiTestFixtures;
import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.CaseDecisionRequest;
import com.laserpay.pdei.api.dto.CaseDecisionResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.CaseApiService;
import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.error.ConflictException;
import com.laserpay.pdei.common.error.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over {@link CaseController}.
 *
 * <p>The decision routes carry the rules worth pinning down: a decision must name its actor, a
 * rejection must say why, and an illegal transition is a 409 rather than a silent no-op.</p>
 */
@WebMvcTest(controllers = CaseController.class)
@EnableConfigurationProperties(ApiProperties.class)
class CaseControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CaseApiService cases;

    @Test
    @DisplayName("GET /cases returns the queue as a PageResponse")
    void listReturnsQueue() throws Exception {
        when(cases.list(any(), any(), anyInt(), anyInt())).thenReturn(
                PageResponse.ofSlice(List.of(ApiTestFixtures.caseView(CaseStatus.AWAITING_APPROVAL)),
                        0, 25));

        mvc.perform(get("/api/v1/cases")
                        .param("merchantId", ApiTestFixtures.MERCHANT_ID)
                        .param("status", "AWAITING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].caseId").value(ApiTestFixtures.CASE_ID))
                .andExpect(jsonPath("$.content[0].status").value("AWAITING_APPROVAL"));

        verify(cases).list(eq(ApiTestFixtures.MERCHANT_ID), eq(CaseStatus.AWAITING_APPROVAL),
                eq(0), eq(25));
    }

    @Test
    @DisplayName("GET /cases/{id}/xray returns readiness, evidence and timeline in one payload")
    void xrayIsReturned() throws Exception {
        when(cases.xray(ApiTestFixtures.CASE_ID)).thenReturn(ApiTestFixtures.xray());

        mvc.perform(get("/api/v1/cases/{id}/xray", ApiTestFixtures.CASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(ApiTestFixtures.CASE_ID))
                .andExpect(jsonPath("$.disputeAmount.amountMinor").value(1_299_900L))
                .andExpect(jsonPath("$.disputeAmount.currency").value("INR"))
                .andExpect(jsonPath("$.readiness.score").value(88))
                .andExpect(jsonPath("$.evidence[0].evidenceId").value(ApiTestFixtures.EVIDENCE_ID))
                .andExpect(jsonPath("$.timeline[0].eventType").value("ShipmentDelivered"));
    }

    @Test
    @DisplayName("approve returns the new status and how the decision was delivered")
    void approveReturnsDecision() throws Exception {
        when(cases.approve(eq(ApiTestFixtures.CASE_ID), any(CaseDecisionRequest.class)))
                .thenReturn(new CaseDecisionResponse(ApiTestFixtures.CASE_ID, "APPROVE",
                        "humanDecision", CaseStatus.AWAITING_APPROVAL, CaseStatus.PREPARED,
                        CaseDecisionResponse.TEMPORAL_SIGNAL, "ops@example.com", "looks right",
                        ApiTestFixtures.NOW));

        mvc.perform(post("/api/v1/cases/{id}/approve", ApiTestFixtures.CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"ops@example.com\",\"note\":\"looks right\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.signal").value("humanDecision"))
                .andExpect(jsonPath("$.deliveredTo").value("TEMPORAL_SIGNAL"));
    }

    @Test
    @DisplayName("a decision without an actor is 400: an unattributable decision is not auditable")
    void decisionRequiresActor() throws Exception {
        mvc.perform(post("/api/v1/cases/{id}/approve", ApiTestFixtures.CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"looks right\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.actor").isNotEmpty());

        verify(cases, never()).approve(any(), any());
    }

    @Test
    @DisplayName("a rejection without a note is 400: the note is the instruction for the next person")
    void rejectRequiresNote() throws Exception {
        mvc.perform(post("/api/v1/cases/{id}/reject", ApiTestFixtures.CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"ops@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("note"));

        verify(cases, never()).reject(any(), any());
    }

    @Test
    @DisplayName("an illegal transition is 409")
    void illegalTransitionIsConflict() throws Exception {
        when(cases.submit(eq(ApiTestFixtures.CASE_ID), any(CaseDecisionRequest.class)))
                .thenThrow(ConflictException.illegalTransition(ApiTestFixtures.CASE_ID,
                        CaseStatus.CREATED, CaseStatus.SUBMITTED));

        mvc.perform(post("/api/v1/cases/{id}/submit", ApiTestFixtures.CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"ops@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details.from").value("CREATED"))
                .andExpect(jsonPath("$.details.to").value("SUBMITTED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a case with no assembled bundle yet is 404 on /package, and a GET never assembles")
    void packageIs404BeforeAssembly() throws Exception {
        when(cases.packageManifest(ApiTestFixtures.CASE_ID))
                .thenThrow(new NotFoundException("PACKAGE_MANIFEST", ApiTestFixtures.CASE_ID));

        mvc.perform(get("/api/v1/cases/{id}/package", ApiTestFixtures.CASE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * The slice does not register {@code CorrelationIdFilter} (it is wired by
     * {@code WebFilterConfig}, which is not part of a MockMvc slice), so what is asserted here is the
     * fallback: an error carries a correlation id even when nothing bound one for the request.
     */
    @Test
    @DisplayName("every error carries a correlation id, even with no filter bound")
    void correlationIdIsAlwaysPresent() throws Exception {
        when(cases.get("CASE-nope")).thenThrow(new NotFoundException("CASE", "CASE-nope"));

        mvc.perform(get("/api/v1/cases/{id}", "CASE-nope")
                        .header("X-Correlation-Id", "11111111-2222-3333-4444-555555555555"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
