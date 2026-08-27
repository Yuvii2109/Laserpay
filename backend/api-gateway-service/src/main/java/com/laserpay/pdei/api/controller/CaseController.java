package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.CaseDecisionRequest;
import com.laserpay.pdei.api.dto.CaseDecisionResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.CaseApiService;
import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.model.CaseXRay;
import com.laserpay.pdei.core.model.PackageManifest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/cases} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET  /cases                   ?status&amp;merchantId
 * GET  /cases/{caseId}
 * GET  /cases/{caseId}/xray     full Case X-Ray payload
 * POST /cases/{caseId}/approve  human approval (Temporal signal)
 * POST /cases/{caseId}/reject
 * POST /cases/{caseId}/submit
 * GET  /cases/{caseId}/package  representment package manifest
 * </pre>
 *
 * <p>The three decision routes are the only place in the platform where a human overrules the
 * automated path, so all three require a named actor and all three are audited. Each returns the
 * status it moved the case to and whether the workflow was actually signalled or the gateway applied
 * its local fallback.</p>
 */
@RestController
@RequestMapping("/api/v1/cases")
@Tag(name = "cases", description = "Case queue, Case X-Ray, human decisions and package manifest")
public class CaseController {

    private final CaseApiService cases;

    public CaseController(CaseApiService cases) {
        this.cases = cases;
    }

    @GetMapping
    @Operation(summary = "Case queue", description = "One swimlane per CaseStatus in the UI.")
    public PageResponse<CaseView> list(
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "status", required = false) CaseStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        return cases.list(merchantId, status, page, size);
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "One case")
    public CaseView get(@PathVariable("caseId") String caseId) {
        return cases.get(caseId);
    }

    @GetMapping("/{caseId}/xray")
    @Operation(summary = "Case X-Ray",
            description = "Readiness, evidence, graph, timeline, gaps, contradictions, the AI "
                    + "proposal and the deterministic verdict applied to it, computed fresh.")
    public CaseXRay xray(@PathVariable("caseId") String caseId) {
        return cases.xray(caseId);
    }

    @GetMapping("/{caseId}/package")
    @Operation(summary = "Representment package manifest",
            description = "404 until the workflow has assembled a bundle. A GET never assembles one.")
    public PackageManifest packageManifest(@PathVariable("caseId") String caseId) {
        return cases.packageManifest(caseId);
    }

    @PostMapping("/{caseId}/approve")
    @Operation(summary = "Approve the prepared representment")
    public CaseDecisionResponse approve(@PathVariable("caseId") String caseId,
                                        @Valid @RequestBody CaseDecisionRequest request) {
        return cases.approve(caseId, request);
    }

    /**
     * A rejection must say why. Approval and submission can reasonably be silent, but a case sent
     * back for more evidence with no reason leaves the next person with nothing to act on, so the
     * note is required here and only here.
     */
    @PostMapping("/{caseId}/reject")
    @Operation(summary = "Reject and return the case for more evidence",
            description = "The note is required: it is the instruction the next person acts on.")
    public CaseDecisionResponse reject(@PathVariable("caseId") String caseId,
                                       @Valid @RequestBody CaseDecisionRequest request) {
        if (request.note() == null || request.note().isBlank()) {
            throw ValidationException.field("note", "is required when rejecting a case");
        }
        return cases.reject(caseId, request);
    }

    @PostMapping("/{caseId}/submit")
    @Operation(summary = "Submit the representment to the network")
    public CaseDecisionResponse submit(@PathVariable("caseId") String caseId,
                                       @Valid @RequestBody CaseDecisionRequest request) {
        return cases.submit(caseId, request);
    }
}
