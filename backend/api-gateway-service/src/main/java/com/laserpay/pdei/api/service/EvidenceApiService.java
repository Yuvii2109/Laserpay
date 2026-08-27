package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.EvidenceUploadRequest;
import com.laserpay.pdei.api.dto.EvidenceVersionsResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.support.Paging;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.core.evidence.CreateEvidenceCommand;
import com.laserpay.pdei.core.evidence.EvidenceIntegrityService;
import com.laserpay.pdei.core.evidence.EvidenceService;
import com.laserpay.pdei.core.evidence.IntegrityReport;
import com.laserpay.pdei.core.model.EvidenceGraph;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.SearchPage;
import com.laserpay.pdei.core.evidence.EvidenceLineageService;
import com.laserpay.pdei.core.search.EvidenceSearchQuery;
import com.laserpay.pdei.core.search.EvidenceSearchService;
import com.laserpay.pdei.core.spi.EvidenceVersionRecord;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /evidence} routes: search, detail, versions, lineage, download, upload and verify.
 *
 * <p>Two of these mutate, and both do so only through {@code evidence-core}. Upload delegates to
 * {@code EvidenceService.createEvidence}, which writes the object first, then the row, then the
 * event, and hashes the bytes it actually stored. Verify delegates to
 * {@code EvidenceIntegrityService}, which re-reads and re-hashes the object and invalidates the
 * artifact on any mismatch. Neither computation is reimplemented here.</p>
 */
@Service
public class EvidenceApiService {

    /** Newest first, with artifacts that carry no creation instant sorted last rather than throwing. */
    private static final Comparator<Instant> NEWEST_FIRST =
            Comparator.nullsLast(Comparator.<Instant>reverseOrder());

    private final EvidenceService evidenceService;
    private final EvidenceSearchService searchService;
    private final EvidenceLineageService lineageService;
    private final EvidenceIntegrityService integrityService;

    public EvidenceApiService(EvidenceService evidenceService,
                              EvidenceSearchService searchService,
                              EvidenceLineageService lineageService,
                              EvidenceIntegrityService integrityService) {
        this.evidenceService = evidenceService;
        this.searchService = searchService;
        this.lineageService = lineageService;
        this.integrityService = integrityService;
    }

    /** {@code GET /evidence?merchantId&type&status&q}: Postgres full-text search over the corpus. */
    @Transactional(readOnly = true)
    public PageResponse<EvidenceView> search(String merchantId, EvidenceType type, EvidenceStatus status,
                                             String q, String transactionId, int page, int size) {
        // EvidenceSearchQuery clamps size and floors page itself, but a negative page is a client
        // mistake worth reporting rather than silently correcting to 0.
        EvidenceSearchQuery query = new EvidenceSearchQuery(
                blankToNull(merchantId), blankToNull(q), type, status, blankToNull(transactionId),
                Paging.page(page), Paging.size(size, EvidenceSearchQuery.MAX_SIZE));
        SearchPage<EvidenceView> result = searchService.search(query);
        return PageResponse.of(result, view -> view);
    }

    /** {@code GET /evidence/{evidenceId}}. 404 when unknown. */
    @Transactional(readOnly = true)
    public EvidenceView get(String evidenceId) {
        return evidenceService.require(evidenceId);
    }

    /**
     * {@code GET /evidence/{evidenceId}/versions}.
     *
     * <p>Returns both ledgers: the evidence row chain (what the merchant calls versions) and the
     * append-only record of objects actually written to MinIO (what an auditor checks).</p>
     */
    @Transactional(readOnly = true)
    public EvidenceVersionsResponse versions(String evidenceId) {
        EvidenceView head = evidenceService.require(evidenceId);
        List<EvidenceView> chain = lineageService.versionChain(evidenceId);
        List<EvidenceVersionRecord> stored = lineageService.storedVersions(evidenceId);
        int currentVersion = chain.stream()
                .mapToInt(EvidenceView::version)
                .max()
                .orElse(head.version());
        return new EvidenceVersionsResponse(evidenceId, currentVersion, chain, stored);
    }

    /** {@code GET /evidence/{evidenceId}/lineage}: version chain plus provenance edges. */
    @Transactional(readOnly = true)
    public EvidenceGraph lineage(String evidenceId) {
        evidenceService.require(evidenceId);
        return lineageService.lineage(evidenceId);
    }

    /**
     * {@code GET /evidence/{evidenceId}/download}: the presigned MinIO URL the controller 302s to.
     *
     * <p>The gateway never proxies the bytes. Streaming a 40 MB PDF through the API would tie up a
     * servlet thread for the length of the download and put the object store's throughput behind the
     * gateway's; a short-lived presigned URL lets the browser fetch directly from MinIO.</p>
     */
    @Transactional(readOnly = true)
    public String downloadUrl(String evidenceId) {
        return evidenceService.downloadUrl(evidenceId);
    }

    /**
     * {@code POST /evidence}: merchant portal upload.
     *
     * <p>Idempotent by content: uploading the same bytes against the same transaction returns the
     * existing artifact instead of creating a duplicate, so a double-clicked upload button is
     * harmless.</p>
     */
    @Transactional
    public EvidenceView upload(EvidenceUploadRequest request, MultipartFile file) {
        byte[] content = readBytes(file);
        CreateEvidenceCommand command = new CreateEvidenceCommand(
                request.merchantId(),
                request.transactionId(),
                request.type(),
                request.effectiveSource(),
                file.getOriginalFilename(),
                file.getContentType(),
                content,
                request.summary(),
                request.sourceEventId(),
                com.laserpay.pdei.api.support.CorrelationIds.currentOrNull(),
                request.relatedEntityId(),
                request.observedAt(),
                request.expiresAt(),
                request.effectiveQualityScore(),
                request.provenanceVerified(),
                request.effectiveActor());
        return evidenceService.createEvidence(command);
    }

    /**
     * {@code POST /evidence/{evidenceId}/verify}: re-hash the stored object and check integrity.
     *
     * <p>A failure here is not an error response: the report is the answer. The artifact is moved to
     * INVALIDATED by the core service and the caller gets a 200 describing exactly what diverged,
     * which is what an operator investigating a tamper alert needs to see.</p>
     */
    @Transactional
    public IntegrityReport verify(String evidenceId) {
        evidenceService.require(evidenceId);
        return integrityService.verify(evidenceId);
    }

    /** Evidence of a transaction, newest first: used by the transaction detail page. */
    @Transactional(readOnly = true)
    public List<EvidenceView> forTransaction(String transactionId) {
        return evidenceService.findForTransaction(transactionId).stream()
                .sorted(Comparator.comparing(EvidenceView::createdAt, NEWEST_FIRST))
                .toList();
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file part is required and must not be empty",
                    Map.of("part", "file"));
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("could not read the uploaded file",
                    Map.of("part", "file", "filename", String.valueOf(file.getOriginalFilename())), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
