package com.laserpay.pdei.docproc.extract;

/**
 * Thrown when an artifact cannot be decoded at all.
 *
 * <p>Deliberately NOT a subclass of {@code com.laserpay.pdei.common.error.PdeiException}: that
 * type is {@code sealed} and its permitted subtypes are frozen by docs/SHARED-LIBRARY-API.md
 * section 1.8. An undecodable document is a document-processor concern, handled locally by the
 * quarantine path rather than surfaced as a platform-level validation error.
 */
public class ExtractionFailedException extends RuntimeException {

    private final String extractor;

    public ExtractionFailedException(String extractor, String message) {
        super(message);
        this.extractor = extractor;
    }

    public ExtractionFailedException(String extractor, String message, Throwable cause) {
        super(message, cause);
        this.extractor = extractor;
    }

    /** Name of the extractor that gave up, for the quarantine record. */
    public String extractor() {
        return extractor;
    }
}
