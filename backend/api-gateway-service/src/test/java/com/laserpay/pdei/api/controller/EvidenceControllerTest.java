package com.laserpay.pdei.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laserpay.pdei.api.ApiTestFixtures;
import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.EvidenceUploadRequest;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.EvidenceApiService;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.error.EvidenceIntegrityException;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.core.evidence.IntegrityReport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over {@link EvidenceController}.
 *
 * <p>The interesting cases here are the ones where the HTTP semantics carry meaning: download is a
 * 302 to a presigned URL rather than a proxied stream, verify answers 200 with a report even when
 * the artifact is broken, and upload is rejected before it reaches storage when its metadata is
 * invalid.</p>
 */
@WebMvcTest(controllers = EvidenceController.class)
@EnableConfigurationProperties(ApiProperties.class)
class EvidenceControllerTest {

    private static final String PRESIGNED =
            "http://localhost:9000/pdei-evidence/MER-0001/TX-000123/DELIVERY_PROOF/EV-000999/v1/proof.pdf?X-Amz-Signature=abc";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EvidenceApiService evidence;

    @Test
    @DisplayName("GET /evidence returns a PageResponse of evidence views")
    void searchReturnsPage() throws Exception {
        when(evidence.search(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(ApiTestFixtures.evidence()), 0, 25, 1L));

        mvc.perform(get("/api/v1/evidence")
                        .param("merchantId", ApiTestFixtures.MERCHANT_ID)
                        .param("q", "delivery signature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].evidenceId").value(ApiTestFixtures.EVIDENCE_ID))
                .andExpect(jsonPath("$.content[0].sha256").isNotEmpty())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(evidence).search(eq(ApiTestFixtures.MERCHANT_ID), eq(null), eq(null),
                eq("delivery signature"), eq(null), eq(0), eq(25));
    }

    @Test
    @DisplayName("type and status filters are parsed into the shared enums")
    void filtersAreParsedIntoEnums() throws Exception {
        when(evidence.search(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 25, 0L));

        mvc.perform(get("/api/v1/evidence")
                        .param("type", "DELIVERY_PROOF")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk());

        verify(evidence).search(eq(null), eq(EvidenceType.DELIVERY_PROOF), eq(EvidenceStatus.ACTIVE),
                eq(null), eq(null), eq(0), eq(25));
    }

    @Test
    @DisplayName("download is a 302 to the presigned URL, never a proxied byte stream")
    void downloadRedirects() throws Exception {
        when(evidence.downloadUrl(ApiTestFixtures.EVIDENCE_ID)).thenReturn(PRESIGNED);

        mvc.perform(get("/api/v1/evidence/{id}/download", ApiTestFixtures.EVIDENCE_ID))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", PRESIGNED));
    }

    @Test
    @DisplayName("verify answers 200 with intact=false when the stored bytes no longer match")
    void verifyReportsMismatchAsTwoHundred() throws Exception {
        when(evidence.verify(ApiTestFixtures.EVIDENCE_ID)).thenReturn(
                IntegrityReport.mismatch(ApiTestFixtures.EVIDENCE_ID, "key", "expected-sha",
                        "actual-sha", ApiTestFixtures.NOW));

        mvc.perform(post("/api/v1/evidence/{id}/verify", ApiTestFixtures.EVIDENCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.expectedSha256").value("expected-sha"))
                .andExpect(jsonPath("$.actualSha256").value("actual-sha"));
    }

    @Test
    @DisplayName("an EvidenceIntegrityException maps to 422")
    void integrityFailureIs422() throws Exception {
        when(evidence.verify("EV-broken")).thenThrow(
                EvidenceIntegrityException.hashMismatch("EV-broken", "a", "b"));

        mvc.perform(post("/api/v1/evidence/{id}/verify", "EV-broken"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INTEGRITY"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("an unknown artifact is 404")
    void unknownEvidenceIs404() throws Exception {
        when(evidence.get("EV-nope")).thenThrow(new NotFoundException("EVIDENCE", "EV-nope"));

        mvc.perform(get("/api/v1/evidence/{id}", "EV-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details.entityId").value("EV-nope"));
    }

    @Test
    @DisplayName("multipart upload returns 201 with a Location header")
    void uploadReturnsCreated() throws Exception {
        when(evidence.upload(any(EvidenceUploadRequest.class), any()))
                .thenReturn(ApiTestFixtures.evidence());

        mvc.perform(multipart("/api/v1/evidence")
                        .file(filePart())
                        .file(metadataPart("""
                                {"merchantId":"MER-0001","transactionId":"TX-000123",
                                 "type":"DELIVERY_PROOF","summary":"signed proof"}""")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/evidence/" + ApiTestFixtures.EVIDENCE_ID))
                .andExpect(jsonPath("$.evidenceId").value(ApiTestFixtures.EVIDENCE_ID));
    }

    @Test
    @DisplayName("invalid metadata is rejected with 400 before anything is stored")
    void uploadRejectsInvalidMetadata() throws Exception {
        mvc.perform(multipart("/api/v1/evidence")
                        .file(filePart())
                        .file(metadataPart("""
                                {"merchantId":"not-a-merchant-id","transactionId":"TX-000123",
                                 "type":"DELIVERY_PROOF"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.merchantId").isNotEmpty());

        verify(evidence, never()).upload(any(), any());
    }

    @Test
    @DisplayName("a missing metadata part is 400, not 500")
    void uploadWithoutMetadataIs400() throws Exception {
        mvc.perform(multipart("/api/v1/evidence").file(filePart()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(evidence, never()).upload(any(), any());
    }

    private static MockMultipartFile filePart() {
        return new MockMultipartFile("file", "proof.pdf", MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile metadataPart(String json) {
        return new MockMultipartFile("metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(StandardCharsets.UTF_8));
    }
}
