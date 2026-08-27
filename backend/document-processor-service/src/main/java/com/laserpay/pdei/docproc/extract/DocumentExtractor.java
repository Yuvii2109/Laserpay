package com.laserpay.pdei.docproc.extract;

/**
 * Turns artifact bytes into searchable text plus metadata.
 *
 * <p>Implementations are stateless and thread-safe: one instance serves every consumer thread and
 * every HTTP request. Selection is by content type and filename through
 * {@link ExtractorRegistry}, never by trying parsers until one stops throwing.
 *
 * <p>Contract for implementors:
 * <ul>
 *   <li>Never mutate {@link ExtractionRequest#content()} - other extractors and the integrity
 *       hash see the same array.</li>
 *   <li>Never perform I/O beyond parsing the supplied bytes. Fetching from MinIO, hashing and
 *       persistence belong to the calling service.</li>
 *   <li>Throw {@link ExtractionFailedException} only when nothing at all could be read. A
 *       partially readable document returns whatever text it has plus a warning; that is what
 *       stops a corrupted last page from erasing four good ones.</li>
 * </ul>
 */
public interface DocumentExtractor {

    /** Stable name recorded in evidence metadata and in {@link ExtractionResult#extractor()}. */
    String name();

    /** Whether this extractor claims the request. Checked in registry order, first match wins. */
    boolean supports(ExtractionRequest request);

    /**
     * Parse the bytes.
     *
     * @throws ExtractionFailedException when the artifact is undecodable
     */
    ExtractionResult extract(ExtractionRequest request);
}
