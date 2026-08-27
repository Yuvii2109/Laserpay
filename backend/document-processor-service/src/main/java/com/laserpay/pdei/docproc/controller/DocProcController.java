package com.laserpay.pdei.docproc.controller;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.docproc.extract.ExtractionResult;
import com.laserpay.pdei.docproc.extract.ExtractorRegistry;
import com.laserpay.pdei.docproc.service.DocProcStats;
import com.laserpay.pdei.docproc.service.DocumentProcessingService;
import com.laserpay.pdei.docproc.service.ProcessingOutcome;
import com.laserpay.pdei.docproc.service.QuarantineService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST surface of document-processor-service, platform contract section 8.3.
 *
 * <pre>
 * POST /docproc/v1/extract               {objectKey} -&gt; {text, metadata, pageCount, sha256}
 * POST /docproc/v1/reprocess/{evidenceId}
 * GET  /docproc/v1/stats
 * </pre>
 *
 * <p>{@code /extract} is read-only: it parses an object and returns what it found, touching
 * neither the database nor Kafka. {@code /reprocess} is the operator's escape hatch - it forces
 * a full pass over one evidence artifact even when the bytes have not changed, which is what you
 * run after fixing whatever put a document in quarantine.
 */
@RestController
@RequestMapping("/docproc/v1")
@Validated
public class DocProcController {

    private static final Logger log = LoggerFactory.getLogger(DocProcController.class);
    private static final int QUARANTINE_PAGE_SIZE = 50;

    private final DocumentProcessingService processingService;
    private final ExtractorRegistry registry;
    private final DocProcStats stats;
    private final QuarantineService quarantine;
    private final Instant startedAt;

    public DocProcController(DocumentProcessingService processingService,
                             ExtractorRegistry registry,
                             DocProcStats stats,
                             QuarantineService quarantine,
                             Clocks clock) {
        this.processingService = processingService;
        this.registry = registry;
        this.stats = stats;
        this.quarantine = quarantine;
        this.startedAt = clock.now();
    }

    /**
     * Extract one object from the store.
     *
     * <p>Diagnostic and read-only. Use it to answer "what text does this artifact actually
     * contain" without waiting for an event, and to check what an extractor will produce before
     * it is written into the search index.
     */
    @PostMapping("/extract")
    public ExtractResponseDto extract(@Valid @RequestBody ExtractRequestDto request) {
        log.debug("extract requested for {} in bucket {}", request.objectKey(), request.bucket());
        ExtractionResult result = processingService.extractObject(request.bucket(), request.objectKey());
        return ExtractResponseDto.from(result);
    }

    /**
     * Re-run extraction for one evidence artifact and write the result.
     *
     * @param force when {@code true} (the default) the unchanged-bytes short-circuit is bypassed
     * @return {@code 200} with the outcome, or {@code 404} when there is no such evidence
     */
    @PostMapping("/reprocess/{evidenceId}")
    public ResponseEntity<ProcessingOutcome> reprocess(
            @PathVariable @NotBlank @Size(max = 64) String evidenceId,
            @RequestParam(defaultValue = "true") boolean force) {
        ProcessingOutcome outcome = processingService.processEvidence(evidenceId, null, force);
        if (outcome.status() == ProcessingOutcome.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(outcome);
        }
        stats.recordOutcome(outcome, "Reprocess", null);
        return ResponseEntity.ok(outcome);
    }

    /** Counters, registered extractors, and the recent quarantine list. */
    @GetMapping("/stats")
    public StatsResponseDto stats() {
        return StatsResponseDto.of(startedAt, stats.snapshot(), registry.names(),
                quarantine.recent(QUARANTINE_PAGE_SIZE));
    }
}
