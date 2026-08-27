package com.laserpay.pdei.docproc.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses the {@link DocumentExtractor} for an artifact, by content type and filename.
 *
 * <p>Order is the whole design. Extractors are consulted in the order they were registered and
 * the first one that {@link DocumentExtractor#supports(ExtractionRequest) claims} the request
 * wins, so the specific readers ({@link PdfBoxDocumentExtractor}, {@link EmlExtractor}) come
 * before the general one ({@link TikaDocumentExtractor}). Selection never works by trying
 * parsers until one stops throwing: that turns a corrupt PDF into a mystery text file and makes
 * the extraction path non-deterministic, which is unacceptable for something that feeds an
 * evidence trail.
 *
 * <p>Immutable and thread-safe once built.
 */
public class ExtractorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtractorRegistry.class);

    private final List<DocumentExtractor> extractors;
    private final Map<String, DocumentExtractor> byName;

    public ExtractorRegistry(List<DocumentExtractor> extractors) {
        Objects.requireNonNull(extractors, "extractors must not be null");
        if (extractors.isEmpty()) {
            throw new IllegalArgumentException("ExtractorRegistry needs at least one extractor");
        }
        this.extractors = List.copyOf(extractors);
        Map<String, DocumentExtractor> index = new LinkedHashMap<>();
        for (DocumentExtractor extractor : this.extractors) {
            DocumentExtractor previous = index.put(extractor.name(), extractor);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate extractor name: " + extractor.name());
            }
        }
        this.byName = Map.copyOf(index);
    }

    /**
     * The extractor for this request.
     *
     * @throws ExtractionFailedException when no extractor claims it, which can only happen if the
     *     catch-all fallback was removed from the registry
     */
    public DocumentExtractor select(ExtractionRequest request) {
        for (DocumentExtractor extractor : extractors) {
            if (extractor.supports(request)) {
                log.debug("selected extractor {} for {} ({})",
                        extractor.name(), request.filename(), request.baseContentType());
                return extractor;
            }
        }
        throw new ExtractionFailedException("registry",
                "no extractor claims " + request.filename() + " (" + request.baseContentType() + ")");
    }

    /** Runs the selected extractor. The single entry point used by the service layer. */
    public ExtractionResult extract(ExtractionRequest request) {
        return select(request).extract(request);
    }

    /** Look up by {@link DocumentExtractor#name()}, for the {@code /stats} endpoint and tests. */
    public Optional<DocumentExtractor> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Registered extractors, in selection order. */
    public List<DocumentExtractor> extractors() {
        return extractors;
    }

    public List<String> names() {
        return extractors.stream().map(DocumentExtractor::name).toList();
    }
}
