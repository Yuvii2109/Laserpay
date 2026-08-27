package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * The general-purpose extractor: Apache Tika auto-detects the format and produces body text plus
 * whatever metadata the format carries.
 *
 * <p>This is the last entry in {@link ExtractorRegistry}, the fallback for everything that does
 * not have a dedicated reader. PDFs go to {@link PdfBoxDocumentExtractor} and {@code .eml} files
 * to {@link EmlExtractor}, because for those two formats PDEI needs structure that a flattened
 * body string does not carry.
 *
 * <p>Bounded by construction: the write limit caps how much text a single document can produce,
 * so a decompression bomb costs a truncation warning instead of the worker's heap. Embedded
 * documents are deliberately not recursed into - {@link ParseContext} is left without a
 * {@link Parser}, which is what tells Tika not to descend.
 */
public class TikaDocumentExtractor implements DocumentExtractor {

    public static final String NAME = "tika";
    /** Simple name of the Tika 2.x exception raised when the write limit is hit. */
    private static final String WRITE_LIMIT_EXCEPTION = "WriteLimitReachedException";

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentExtractor.class);

    private final Clocks clock;
    private final int writeLimitChars;

    public TikaDocumentExtractor(Clocks clock, int writeLimitChars) {
        this.clock = clock;
        this.writeLimitChars = writeLimitChars <= 0 ? 1_000_000 : writeLimitChars;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** The fallback: claims everything. Registry order is what keeps it last. */
    @Override
    public boolean supports(ExtractionRequest request) {
        return true;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        ExtractionResult.Builder result = ExtractionResult.builder()
                .extractor(NAME)
                .sizeBytes(request.size())
                .sha256(Hashes.sha256(request.content()))
                .extractedAt(clock.now());

        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, request.filename());
        if (request.contentType() != null) {
            metadata.set(Metadata.CONTENT_TYPE, request.contentType());
        }

        BodyContentHandler handler = new BodyContentHandler(writeLimitChars);
        boolean truncated = false;

        try (InputStream in = new ByteArrayInputStream(request.content())) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (SAXException e) {
            if (isWriteLimitReached(e)) {
                truncated = true;
                result.warn("text truncated at the " + writeLimitChars + " character write limit");
            } else {
                throw new ExtractionFailedException(NAME,
                        "Tika could not read " + request.objectKey() + ": " + e.getMessage(), e);
            }
        } catch (TikaException | IOException | RuntimeException e) {
            throw new ExtractionFailedException(NAME,
                    "Tika could not read " + request.objectKey() + ": " + e.getMessage(), e);
        }

        String detectedType = metadata.get(Metadata.CONTENT_TYPE);
        result.contentType(detectedType != null ? detectedType : request.contentType());
        copyMetadata(metadata, result);
        warnOnImageWithoutOcr(detectedType, result);

        String text = TextNormalizer.normalize(handler.toString());
        result.text(text).truncated(truncated);
        if (text.isBlank()) {
            result.warn("no text content produced by Tika");
        }
        return result.build();
    }

    /**
     * Copies Tika metadata under a {@code tika.} prefix. Prefixing matters: the evidence metadata
     * JSONB column mixes keys from several producers, and an un-namespaced {@code title} from a
     * spreadsheet would silently collide with the evidence title written by state-builder-worker.
     */
    private void copyMetadata(Metadata metadata, ExtractionResult.Builder result) {
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            result.meta("tika." + name, value);
        }
    }

    /**
     * Images have no text layer and PDEI does not run OCR (reference section 25). Say so in the
     * result rather than letting the evidence look merely empty.
     */
    private void warnOnImageWithoutOcr(String detectedType, ExtractionResult.Builder result) {
        if (detectedType != null && detectedType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            result.warn("image artifact: no text extracted, OCR is out of scope for this build");
        }
    }

    /**
     * Tika signals the write limit with a {@code WriteLimitReachedException} (a
     * {@link SAXException} subtype). Matched by simple name rather than by import so the code
     * survives the class moving package between Tika minor versions - the alternative is a
     * compile break on an upgrade that changed nothing that matters here.
     */
    private static boolean isWriteLimitReached(SAXException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (WRITE_LIMIT_EXCEPTION.equals(t.getClass().getSimpleName())) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("Your document contained more than")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        log.debug("SAXException from Tika was not a write-limit signal: {}", e.toString());
        return false;
    }
}
