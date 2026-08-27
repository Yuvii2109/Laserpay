package com.laserpay.pdei.api.controller.aitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laserpay.pdei.api.ApiTestFixtures;
import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.dto.ContradictionsResponse;
import com.laserpay.pdei.api.dto.RelatedEvidenceResponse;
import com.laserpay.pdei.api.dto.RequirementsResponse;
import com.laserpay.pdei.api.dto.TimelineResponse;
import com.laserpay.pdei.api.error.GlobalExceptionHandler;
import com.laserpay.pdei.api.security.ServiceTokenFilter;
import com.laserpay.pdei.api.service.AiToolsService;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.policy.RequirementSpec;
import com.laserpay.pdei.common.domain.RequirementStrength;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The AI tool surface: the ten read-only lookups of contract section 8.6, the service-token guard,
 * and the structural guarantee that nothing under {@code /ai-tools} can mutate.
 *
 * <p>Built with a standalone MockMvc rather than {@code @WebMvcTest} so that
 * {@link ServiceTokenFilter} is genuinely in the chain. A slice test would register the controller
 * without the filter and would then happily prove that unauthenticated requests succeed, which is
 * the exact opposite of what needs proving.</p>
 */
class AiToolsControllerTest {

    private static final String TOKEN = "test-service-token";
    private static final String HEADER = ServiceTokenFilter.HEADER;

    private AiToolsService tools;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tools = Mockito.mock(AiToolsService.class);
        ApiProperties properties = new ApiProperties();
        properties.setServiceToken(TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new AiToolsController(tools))
                .addFilters(new ServiceTokenFilter(properties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Json.mapper()))
                .build();
    }

    // -----------------------------------------------------------------------------------
    // The structural guarantee
    // -----------------------------------------------------------------------------------

    /**
     * Read-only by construction, asserted by reflection so it survives a future edit.
     *
     * <p>A reviewer can forget; this cannot. If anyone ever adds a POST, PUT, PATCH or DELETE handler
     * to the AI tool controller, this test fails and names the method, which is the whole reason the
     * assertion exists rather than a comment saying "please do not".</p>
     */
    @Test
    @DisplayName("no mutating verb is exposed anywhere under /ai-tools")
    void noMutatingMappingsExist() {
        List<String> offenders = new ArrayList<>();
        for (Method method : AiToolsController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            if (method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)) {
                offenders.add(method.getName() + " declares a mutating mapping annotation");
                continue;
            }
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            if (mapping != null) {
                List<RequestMethod> nonGet = Arrays.stream(mapping.method())
                        .filter(verb -> verb != RequestMethod.GET && verb != RequestMethod.HEAD
                                && verb != RequestMethod.OPTIONS)
                        .toList();
                if (mapping.method().length == 0 || !nonGet.isEmpty()) {
                    offenders.add(method.getName() + " maps " + Arrays.toString(mapping.method()));
                }
            }
        }
        assertThat(offenders)
                .as("AiToolsController must expose GET only: the LLM never mutates financial state")
                .isEmpty();
    }

    @Test
    @DisplayName("a mutating request to an ai-tools path is never routed to a handler")
    void mutatingRequestsAreNotRouted() throws Exception {
        String path = "/api/v1/ai-tools/transaction/" + ApiTestFixtures.TRANSACTION_ID;
        mvc.perform(post(path).header(HEADER, TOKEN)).andExpect(status().is4xxClientError());
        mvc.perform(put(path).header(HEADER, TOKEN)).andExpect(status().is4xxClientError());
        mvc.perform(patch(path).header(HEADER, TOKEN)).andExpect(status().is4xxClientError());
        mvc.perform(delete(path).header(HEADER, TOKEN)).andExpect(status().is4xxClientError());
        verifyNoInteractions(tools);
    }

    // -----------------------------------------------------------------------------------
    // The service-token guard
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a request with no service token is 401 and never reaches the service")
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/ai-tools/transaction/{id}", ApiTestFixtures.TRANSACTION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.details.header").value(HEADER));
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("a request with the wrong service token is 401")
    void wrongTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/ai-tools/transaction/{id}", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, "not-the-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tools);
    }

    @Test
    @DisplayName("a blank configured token refuses everything rather than authenticating everyone")
    void blankConfiguredTokenRefuses() throws Exception {
        ApiProperties blank = new ApiProperties();
        blank.setServiceToken("");
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new AiToolsController(tools))
                .addFilters(new ServiceTokenFilter(blank))
                .build();

        unconfigured.perform(get("/api/v1/ai-tools/transaction/{id}", ApiTestFixtures.TRANSACTION_ID))
                .andExpect(status().isUnauthorized());
        unconfigured.perform(get("/api/v1/ai-tools/transaction/{id}", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, ""))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tools);
    }

    // -----------------------------------------------------------------------------------
    // The ten lookups
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /ai-tools/transaction/{id} returns the canonical facts projection")
    void transactionFactsAreReturned() throws Exception {
        when(tools.transaction(ApiTestFixtures.TRANSACTION_ID)).thenReturn(ApiTestFixtures.facts());

        mvc.perform(get("/api/v1/ai-tools/transaction/{id}", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(ApiTestFixtures.TRANSACTION_ID))
                .andExpect(jsonPath("$.amount.amountMinor").value(1_299_900L))
                .andExpect(jsonPath("$.amount.currency").value("INR"))
                .andExpect(jsonPath("$.payments[0].paymentId").value("PAY-000001"));
    }

    @Test
    @DisplayName("GET /ai-tools/evidence/related lists usable and unusable artifacts alike")
    void relatedEvidenceIncludesUnusableArtifacts() throws Exception {
        when(tools.relatedEvidence(ApiTestFixtures.TRANSACTION_ID)).thenReturn(
                RelatedEvidenceResponse.of(ApiTestFixtures.TRANSACTION_ID,
                        List.of(ApiTestFixtures.evidence(),
                                ApiTestFixtures.evidence("EV-000998", EvidenceType.INVOICE,
                                        EvidenceStatus.INVALIDATED)),
                        ApiTestFixtures.NOW));

        mvc.perform(get("/api/v1/ai-tools/evidence/related")
                        .param("transactionId", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.usableCount").value(1));
    }

    /**
     * {@code /evidence/related} is a literal path and {@code /evidence/{id}} is a template; Spring
     * prefers the literal. Without that preference the related lookup would be swallowed by the
     * by-id route and the model would get a 404 for an id of "related".
     */
    @Test
    @DisplayName("/evidence/related is not swallowed by /evidence/{id}")
    void literalPathWinsOverTemplate() throws Exception {
        when(tools.relatedEvidence(any())).thenReturn(
                RelatedEvidenceResponse.of(ApiTestFixtures.TRANSACTION_ID, List.of(),
                        ApiTestFixtures.NOW));

        mvc.perform(get("/api/v1/ai-tools/evidence/related")
                        .param("transactionId", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk());

        verify(tools).relatedEvidence(ApiTestFixtures.TRANSACTION_ID);
        Mockito.verify(tools, Mockito.never()).evidence(any());
    }

    @Test
    @DisplayName("GET /ai-tools/evidence/{id} returns the artifact with its hash")
    void evidenceByIdIsReturned() throws Exception {
        when(tools.evidence(ApiTestFixtures.EVIDENCE_ID)).thenReturn(ApiTestFixtures.evidence());

        mvc.perform(get("/api/v1/ai-tools/evidence/{id}", ApiTestFixtures.EVIDENCE_ID)
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceId").value(ApiTestFixtures.EVIDENCE_ID))
                .andExpect(jsonPath("$.sha256").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /ai-tools/contradictions computes fresh conflicts for a transaction")
    void contradictionsAreReturned() throws Exception {
        when(tools.contradictions(ApiTestFixtures.TRANSACTION_ID)).thenReturn(
                ContradictionsResponse.of(ApiTestFixtures.TRANSACTION_ID, List.of(),
                        ApiTestFixtures.NOW));

        mvc.perform(get("/api/v1/ai-tools/contradictions")
                        .param("transactionId", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(ApiTestFixtures.TRANSACTION_ID))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /ai-tools/requirements parses the reason code and reports the policy version")
    void requirementsAreReturned() throws Exception {
        when(tools.requirements(eq(DisputeReasonCode.GOODS_NOT_RECEIVED), eq(null))).thenReturn(
                RequirementsResponse.of(null, DisputeReasonCode.GOODS_NOT_RECEIVED, null, null, true,
                        List.of(RequirementSpec.of(EvidenceType.DELIVERY_PROOF,
                                RequirementStrength.MANDATORY))));

        mvc.perform(get("/api/v1/ai-tools/requirements")
                        .param("reasonCode", "GOODS_NOT_RECEIVED")
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("GOODS_NOT_RECEIVED"))
                .andExpect(jsonPath("$.defaultPolicy").value(true))
                .andExpect(jsonPath("$.mandatoryCount").value(1))
                .andExpect(jsonPath("$.requirements[0].type").value("DELIVERY_PROOF"));
    }

    @Test
    @DisplayName("GET /ai-tools/timeline/{id} returns the merged timeline")
    void timelineIsReturned() throws Exception {
        when(tools.timeline(ApiTestFixtures.TRANSACTION_ID)).thenReturn(
                TimelineResponse.of(ApiTestFixtures.TRANSACTION_ID,
                        List.of(ApiTestFixtures.timelineEntry()), ApiTestFixtures.NOW));

        mvc.perform(get("/api/v1/ai-tools/timeline/{id}", ApiTestFixtures.TRANSACTION_ID)
                        .header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].eventType").value("ShipmentDelivered"));
    }

    @Test
    @DisplayName("an unknown order is 404 with the shared ErrorResponse shape")
    void unknownOrderIs404() throws Exception {
        when(tools.order("ORD-nope")).thenThrow(new NotFoundException("ORDER", "ORD-nope"));

        mvc.perform(get("/api/v1/ai-tools/order/{id}", "ORD-nope").header(HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
