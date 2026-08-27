package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.CaseDecisionRequest;
import com.laserpay.pdei.api.dto.CaseDecisionResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.support.Paging;
import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.core.dispute.CaseAssemblyService;
import com.laserpay.pdei.core.model.CaseView;
import com.laserpay.pdei.core.model.CaseXRay;
import com.laserpay.pdei.core.model.PackageManifest;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /cases} routes: the queue, the Case X-Ray, the three human decisions and the package
 * manifest.
 *
 * <p>{@code xray} is the heaviest read in the API. It recomputes readiness, loads every artifact,
 * builds the graph and the timeline, and pulls the latest investigation and its safety verdict, so
 * that the Case X-Ray page shows the deterministic truth as of now rather than as of whenever the
 * workflow last wrote a snapshot. That is the right trade: this page exists to be scrutinised.</p>
 *
 * <p>The three decision methods carry no transaction annotation on purpose. They delegate to
 * {@link CaseSignalGateway}, which calls case-orchestrator-service over HTTP before
 * {@link CaseTransitionWriter} opens a transaction for the local writes. A transaction started here
 * would wrap the remote call and hold a database connection across it.</p>
 */
@Service
public class CaseApiService {

    private static final String ENTITY_TYPE = "CASE";

    private final CaseRepositoryPort cases;
    private final CaseAssemblyService assemblyService;
    private final CaseSignalGateway signalGateway;

    public CaseApiService(CaseRepositoryPort cases,
                          CaseAssemblyService assemblyService,
                          CaseSignalGateway signalGateway) {
        this.cases = cases;
        this.assemblyService = assemblyService;
        this.signalGateway = signalGateway;
    }

    /** {@code GET /cases?status&merchantId}: the case queue, one swimlane per status. */
    @Transactional(readOnly = true)
    public PageResponse<CaseView> list(String merchantId, CaseStatus status, int page, int size) {
        // Validated before the port sees them: a negative page becomes a negative OFFSET in SQL.
        int safePage = Paging.page(page);
        int safeSize = Paging.size(size, Paging.MAX_SIZE);
        List<CaseView> slice = cases.findCases(blankToNull(merchantId), status, safePage, safeSize);
        return PageResponse.ofSlice(slice, safePage, safeSize);
    }

    /** {@code GET /cases/{caseId}}. */
    @Transactional(readOnly = true)
    public CaseView get(String caseId) {
        return cases.findCase(caseId).orElseThrow(() -> new NotFoundException(ENTITY_TYPE, caseId));
    }

    /** {@code GET /cases/{caseId}/xray}: the full Case X-Ray payload. */
    @Transactional(readOnly = true)
    public CaseXRay xray(String caseId) {
        return assemblyService.xray(caseId);
    }

    /**
     * {@code GET /cases/{caseId}/package}: the manifest of the representment bundle.
     *
     * <p>Read only, and 404 when nothing has been assembled yet. Assembly is a workflow activity
     * (contract section 10 step 9): a GET must never be the thing that builds a bundle, or refreshing
     * the page would mint a new package version.</p>
     */
    @Transactional(readOnly = true)
    public PackageManifest packageManifest(String caseId) {
        get(caseId);
        return cases.findLatestManifest(caseId)
                .orElseThrow(() -> new NotFoundException("PACKAGE_MANIFEST", caseId));
    }

    /** {@code POST /cases/{caseId}/approve}. */
    public CaseDecisionResponse approve(String caseId, CaseDecisionRequest request) {
        return signalGateway.decide(caseId, CaseDecision.APPROVE, request);
    }

    /** {@code POST /cases/{caseId}/reject}. */
    public CaseDecisionResponse reject(String caseId, CaseDecisionRequest request) {
        return signalGateway.decide(caseId, CaseDecision.REJECT, request);
    }

    /** {@code POST /cases/{caseId}/submit}. */
    public CaseDecisionResponse submit(String caseId, CaseDecisionRequest request) {
        return signalGateway.decide(caseId, CaseDecision.SUBMIT, request);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
