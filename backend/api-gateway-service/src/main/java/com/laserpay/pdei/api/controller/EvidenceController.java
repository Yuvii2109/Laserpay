package com.laserpay.pdei.api.controller;

import com.laserpay.pdei.api.dto.EvidenceUploadRequest;
import com.laserpay.pdei.api.dto.EvidenceVersionsResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.service.EvidenceApiService;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.core.evidence.IntegrityReport;
import com.laserpay.pdei.core.model.EvidenceGraph;
import com.laserpay.pdei.core.model.EvidenceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/v1/evidence} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <pre>
 * GET  /evidence                        ?merchantId&amp;type&amp;status&amp;q (full-text)
 * GET  /evidence/{evidenceId}
 * GET  /evidence/{evidenceId}/versions
 * GET  /evidence/{evidenceId}/lineage
 * GET  /evidence/{evidenceId}/download  302 to a presigned MinIO URL
 * POST /evidence                        multipart upload (merchant portal)
 * POST /evidence/{evidenceId}/verify    re-hash and integrity check
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/evidence")
@Tag(name = "evidence", description = "Evidence explorer, versions, lineage, upload and integrity")
public class EvidenceController {

    private final EvidenceApiService evidence;

    public EvidenceController(EvidenceApiService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    @Operation(summary = "Search evidence",
            description = "Postgres full-text search over summary and extracted text when q is "
                    + "supplied; a plain filtered listing when it is not.")
    public PageResponse<EvidenceView> search(
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "type", required = false) EvidenceType type,
            @RequestParam(name = "status", required = false) EvidenceStatus status,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "transactionId", required = false) String transactionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        return evidence.search(merchantId, type, status, q, transactionId, page, size);
    }

    @GetMapping("/{evidenceId}")
    @Operation(summary = "One evidence artifact")
    public EvidenceView get(@PathVariable("evidenceId") String evidenceId) {
        return evidence.get(evidenceId);
    }

    @GetMapping("/{evidenceId}/versions")
    @Operation(summary = "Version chain and stored object versions",
            description = "Two ledgers: the evidence row lineage the merchant sees as versions, and "
                    + "the append-only record of objects actually written to MinIO.")
    public EvidenceVersionsResponse versions(@PathVariable("evidenceId") String evidenceId) {
        return evidence.versions(evidenceId);
    }

    @GetMapping("/{evidenceId}/lineage")
    @Operation(summary = "Provenance graph for one artifact")
    public EvidenceGraph lineage(@PathVariable("evidenceId") String evidenceId) {
        return evidence.lineage(evidenceId);
    }

    /**
     * 302 to a short-lived presigned URL rather than streaming the bytes.
     *
     * <p>Proxying the file would hold a servlet thread for the whole download and put MinIO's
     * throughput behind the gateway's. The redirect is also what makes {@code Location} worth
     * exposing through CORS.</p>
     */
    @GetMapping("/{evidenceId}/download")
    @Operation(summary = "Redirect to a presigned download URL")
    public ResponseEntity<Void> download(@PathVariable("evidenceId") String evidenceId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(evidence.downloadUrl(evidenceId)))
                .build();
    }

    /**
     * Merchant portal upload: {@code multipart/form-data} with a {@code file} part and a JSON
     * {@code metadata} part.
     *
     * <p>201 with a {@code Location} header. Idempotent by content: the same bytes on the same
     * transaction return the existing artifact rather than creating a second one.</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload evidence",
            description = "Two parts: file (the bytes) and metadata (JSON). The sha256 is computed "
                    + "from the stored bytes and is never accepted from the uploader.")
    public ResponseEntity<EvidenceView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") @Valid EvidenceUploadRequest metadata) {
        EvidenceView created = evidence.upload(metadata, file);
        return ResponseEntity
                .created(URI.create("/api/v1/evidence/" + created.evidenceId()))
                .body(created);
    }

    /**
     * Re-hash the stored object and compare it with the recorded sha256.
     *
     * <p>A mismatch is a 200 carrying an {@code intact: false} report, not an error status: the
     * caller asked whether the artifact is intact and this is the answer. The artifact is moved to
     * INVALIDATED by the core service as a side effect, and the finding is audited.</p>
     */
    @PostMapping("/{evidenceId}/verify")
    @Operation(summary = "Verify stored-object integrity")
    public IntegrityReport verify(@PathVariable("evidenceId") String evidenceId) {
        return evidence.verify(evidenceId);
    }
}
