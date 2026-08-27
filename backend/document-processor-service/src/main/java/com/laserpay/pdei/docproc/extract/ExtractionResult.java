package com.laserpay.pdei.docproc.extract;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The searchable, verifiable form of an evidence artifact.
 *
 * <p>This record backs the response of {@code POST /docproc/v1/extract} (platform contract 8.3):
 * {@code text}, {@code metadata}, {@code pageCount} and {@code sha256} are the four contract
 * fields; the rest is provenance for the audit trail and for the operator reading {@code /stats}
 * and wondering why a document produced nothing.
 *
 * @param text        normalised plain text, whitespace-collapsed, possibly truncated
 * @param metadata    document metadata (author, title, producer, email headers, ...)
 * @param pageCount   pages for paginated formats, 0 when the concept does not apply
 * @param sha256      hash of the exact bytes that were parsed
 * @param pages       per-page text for paginated formats; empty otherwise
 * @param extractor   name of the {@link DocumentExtractor} that produced this result
 * @param contentType MIME type the extractor believes the bytes to be
 * @param sizeBytes   size of the parsed bytes
 * @param truncated   whether {@code text} was cut at the configured character ceiling
 * @param warnings    non-fatal problems (missing text layer, unreadable attachment, ...)
 * @param extractedAt when extraction finished
 */
public record ExtractionResult(String text,
                               Map<String, String> metadata,
                               int pageCount,
                               String sha256,
                               List<String> pages,
                               String extractor,
                               String contentType,
                               long sizeBytes,
                               boolean truncated,
                               List<String> warnings,
                               Instant extractedAt) {

    public ExtractionResult {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        pages = pages == null ? List.of() : List.copyOf(pages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public int characterCount() {
        return text.length();
    }

    /** True when the artifact yielded no usable text - the trigger for the quarantine path. */
    public boolean isEmpty() {
        return text.isBlank();
    }

    /** Copy with the text replaced, used when the caller applies its own truncation ceiling. */
    public ExtractionResult withText(String newText, boolean nowTruncated) {
        return new ExtractionResult(newText, metadata, pageCount, sha256, pages, extractor,
                contentType, sizeBytes, nowTruncated || truncated, warnings, extractedAt);
    }

    /** Copy with one extra warning appended. */
    public ExtractionResult withWarning(String warning) {
        if (warning == null || warning.isBlank()) {
            return this;
        }
        List<String> merged = new ArrayList<>(warnings);
        merged.add(warning);
        return new ExtractionResult(text, metadata, pageCount, sha256, pages, extractor,
                contentType, sizeBytes, truncated, List.copyOf(merged), extractedAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable accumulator; extractors fill it as they walk the document. */
    public static final class Builder {

        private static final int MAX_METADATA_VALUE_CHARS = 2000;

        private String text = "";
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private int pageCount;
        private String sha256;
        private List<String> pages = List.of();
        private String extractor = "unknown";
        private String contentType;
        private long sizeBytes;
        private boolean truncated;
        private final List<String> warnings = new ArrayList<>();
        private Instant extractedAt = Instant.EPOCH;

        private Builder() {
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** Records a metadata entry; blank keys and values are dropped rather than stored empty. */
        public Builder meta(String key, String value) {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                String trimmed = value.strip();
                metadata.put(key, trimmed.length() > MAX_METADATA_VALUE_CHARS
                        ? trimmed.substring(0, MAX_METADATA_VALUE_CHARS)
                        : trimmed);
            }
            return this;
        }

        public Builder pageCount(int pageCount) {
            this.pageCount = Math.max(0, pageCount);
            return this;
        }

        public Builder sha256(String sha256) {
            this.sha256 = sha256;
            return this;
        }

        public Builder pages(List<String> pages) {
            this.pages = pages == null ? List.of() : List.copyOf(pages);
            return this;
        }

        public Builder extractor(String extractor) {
            this.extractor = extractor;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder sizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder warn(String warning) {
            if (warning != null && !warning.isBlank()) {
                warnings.add(warning);
            }
            return this;
        }

        public Builder extractedAt(Instant extractedAt) {
            this.extractedAt = extractedAt;
            return this;
        }

        public ExtractionResult build() {
            return new ExtractionResult(text, metadata, pageCount, sha256, pages, extractor,
                    contentType, sizeBytes, truncated, warnings, extractedAt);
        }
    }
}
