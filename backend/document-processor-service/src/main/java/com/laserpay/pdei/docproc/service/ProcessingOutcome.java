package com.laserpay.pdei.docproc.service;

import com.laserpay.pdei.common.metrics.MetricNames;

import java.time.Instant;
import java.util.List;

/**
 * What happened to one evidence artifact. Returned by
 * {@link DocumentProcessingService#processEvidence(String, String, boolean)} and rendered by
 * {@code POST /docproc/v1/reprocess/{evidenceId}}.
 *
 * @param evidenceId  artifact that was processed
 * @param status      terminal state of this attempt
 * @param extractor   extractor that ran, or null when none did
 * @param sha256      hash of the bytes actually read from the object store
 * @param pageCount   pages for paginated formats
 * @param characters  characters written into {@code evidence.extracted_text}
 * @param integrityOk whether the recomputed sha256 matched the one recorded on the evidence row
 * @param warnings    non-fatal problems worth showing an operator
 * @param message     human-readable summary, always populated
 * @param at          when this attempt finished
 */
public record ProcessingOutcome(String evidenceId,
                                Status status,
                                String extractor,
                                String sha256,
                                int pageCount,
                                int characters,
                                boolean integrityOk,
                                List<String> warnings,
                                String message,
                                Instant at) {

    /** Terminal states of one processing attempt. */
    public enum Status {
        /** Text and metadata were written to the evidence row. */
        EXTRACTED,
        /** Bytes unchanged since the last successful extraction; nothing to do. */
        SKIPPED_UNCHANGED,
        /** Evidence row carries no {@code objectKey}: derived evidence with no artifact. */
        SKIPPED_NO_OBJECT,
        /** Artifact could not be used; see {@link QuarantineService} for the reason. */
        QUARANTINED,
        /** No such evidence id. */
        NOT_FOUND
    }

    public ProcessingOutcome {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean isSuccess() {
        return status == Status.EXTRACTED;
    }

    /**
     * The {@code outcome} tag for {@code pdei_events_processed_total}.
     *
     * <p>Mapped onto {@link com.laserpay.pdei.common.metrics.MetricNames.Outcome} rather than
     * emitting the status name directly: that tag has a bounded vocabulary shared by every
     * service, and widening it per-service is what turns a cross-service dashboard into five
     * incompatible panels. The finer detail lives on the service-local
     * {@code pdei_docproc_quarantined_total{reason}} counter and in {@code /docproc/v1/stats}.
     */
    public String outcomeTag() {
        return switch (status) {
            case EXTRACTED -> MetricNames.Outcome.SUCCESS;
            case QUARANTINED -> MetricNames.Outcome.FAILURE;
            case SKIPPED_UNCHANGED, SKIPPED_NO_OBJECT, NOT_FOUND -> MetricNames.Outcome.SKIPPED;
        };
    }

    static ProcessingOutcome of(String evidenceId, Status status, String message, Instant at) {
        return new ProcessingOutcome(evidenceId, status, null, null, 0, 0, true, List.of(), message, at);
    }
}
