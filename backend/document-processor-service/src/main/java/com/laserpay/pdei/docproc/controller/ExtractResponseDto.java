package com.laserpay.pdei.docproc.controller;

import com.laserpay.pdei.docproc.extract.ExtractionResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code POST /docproc/v1/extract}.
 *
 * <p>The first four fields are the platform contract (section 8.3):
 * {@code {text, metadata, pageCount, sha256}}. The rest is provenance the UI shows next to an
 * evidence artifact - which parser ran, whether the text was truncated, and what went wrong
 * without failing.
 *
 * <p>Per-page text is deliberately not returned here: for a 500-page PDF it would multiply the
 * response size for information the caller of an ad-hoc extraction has not asked for. It is
 * available in {@link ExtractionResult} to callers inside the service.
 */
public record ExtractResponseDto(String text,
                                 Map<String, String> metadata,
                                 int pageCount,
                                 String sha256,
                                 String extractor,
                                 String contentType,
                                 long sizeBytes,
                                 int characters,
                                 boolean truncated,
                                 List<String> warnings,
                                 Instant extractedAt) {

    public static ExtractResponseDto from(ExtractionResult result) {
        return new ExtractResponseDto(
                result.text(),
                result.metadata(),
                result.pageCount(),
                result.sha256(),
                result.extractor(),
                result.contentType(),
                result.sizeBytes(),
                result.characterCount(),
                result.truncated(),
                result.warnings(),
                result.extractedAt());
    }
}
