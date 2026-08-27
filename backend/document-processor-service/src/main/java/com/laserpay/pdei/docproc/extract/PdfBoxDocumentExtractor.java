package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * PDF-specific extraction: page count, embedded document information, and text per page.
 *
 * <p>PDFBox rather than Tika for PDFs because the three things PDEI needs from a PDF - how many
 * pages it has, which page a claim came from, and what the producing system stamped into the
 * document information dictionary - are all things the generic Tika pipeline flattens away.
 * "The delivery signature is on page 3 of EV-4F2A" is a citation an investigator can check;
 * "the delivery signature is somewhere in EV-4F2A" is not.
 *
 * <p>No OCR. Reference section 25 puts OCR explicitly out of scope for this build: a scanned PDF
 * with no text layer returns empty text plus the {@link #WARNING_NO_TEXT_LAYER} warning, and the
 * calling service routes it to quarantine. That is a deliberate, visible gap rather than a
 * silently empty search index.
 */
public class PdfBoxDocumentExtractor implements DocumentExtractor {

    public static final String NAME = "pdfbox";
    public static final String WARNING_NO_TEXT_LAYER =
            "PDF has no extractable text layer (scanned image?); OCR is out of scope";

    private static final Logger log = LoggerFactory.getLogger(PdfBoxDocumentExtractor.class);
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final Clocks clock;
    private final int maxPages;

    public PdfBoxDocumentExtractor(Clocks clock, int maxPages) {
        this.clock = clock;
        this.maxPages = maxPages <= 0 ? Integer.MAX_VALUE : maxPages;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(ExtractionRequest request) {
        return PDF_CONTENT_TYPE.equals(request.baseContentType())
                || "pdf".equals(request.extension())
                || hasPdfMagic(request.content());
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        ExtractionResult.Builder result = ExtractionResult.builder()
                .extractor(NAME)
                .contentType(PDF_CONTENT_TYPE)
                .sizeBytes(request.size())
                .sha256(Hashes.sha256(request.content()))
                .extractedAt(clock.now());

        try (PDDocument document = Loader.loadPDF(request.content())) {
            int totalPages = document.getNumberOfPages();
            result.pageCount(totalPages);
            result.meta("pdf.pageCount", Integer.toString(totalPages));
            result.meta("pdf.version", Float.toString(document.getVersion()));
            result.meta("pdf.encrypted", Boolean.toString(document.isEncrypted()));
            readDocumentInformation(document, result);

            int pagesToRead = Math.min(totalPages, maxPages);
            if (pagesToRead < totalPages) {
                result.warn("page limit reached: read " + pagesToRead + " of " + totalPages + " pages");
            }

            List<String> pages = new ArrayList<>(pagesToRead);
            StringBuilder whole = new StringBuilder();
            for (int page = 1; page <= pagesToRead; page++) {
                String pageText = readPage(document, page, result);
                pages.add(pageText);
                if (!pageText.isBlank()) {
                    if (whole.length() > 0) {
                        whole.append("\n\n");
                    }
                    whole.append(pageText);
                }
            }

            String text = TextNormalizer.normalize(whole.toString());
            result.pages(pages).text(text);
            if (text.isBlank()) {
                result.warn(WARNING_NO_TEXT_LAYER);
            }
            return result.build();

        } catch (InvalidPasswordException e) {
            throw new ExtractionFailedException(NAME,
                    "PDF is password protected: " + request.objectKey(), e);
        } catch (IOException | RuntimeException e) {
            throw new ExtractionFailedException(NAME,
                    "PDF could not be parsed: " + request.objectKey() + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Text of a single page. A page that throws is recorded as a warning and skipped rather than
     * failing the document: partial evidence beats no evidence.
     */
    private String readPage(PDDocument document, int page, ExtractionResult.Builder result) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            stripper.setSortByPosition(true);
            return TextNormalizer.normalize(stripper.getText(document));
        } catch (IOException | RuntimeException e) {
            log.warn("page {} of PDF could not be read: {}", page, e.toString());
            result.warn("page " + page + " unreadable: " + e.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * The document information dictionary. This is provenance: the producing system, the author
     * and the creation date of a delivery note are exactly what an investigator checks when two
     * delivery proofs disagree.
     */
    private void readDocumentInformation(PDDocument document, ExtractionResult.Builder result) {
        PDDocumentInformation info = document.getDocumentInformation();
        if (info == null) {
            return;
        }
        result.meta("pdf.title", info.getTitle());
        result.meta("pdf.author", info.getAuthor());
        result.meta("pdf.subject", info.getSubject());
        result.meta("pdf.keywords", info.getKeywords());
        result.meta("pdf.creator", info.getCreator());
        result.meta("pdf.producer", info.getProducer());
        result.meta("pdf.creationDate", toIsoInstant(info.getCreationDate()));
        result.meta("pdf.modificationDate", toIsoInstant(info.getModificationDate()));
    }

    /** PDF dates are {@link Calendar}; the platform stores ISO-8601 UTC and nothing else. */
    private static String toIsoInstant(Calendar calendar) {
        if (calendar == null) {
            return null;
        }
        return Instant.ofEpochMilli(calendar.getTimeInMillis()).toString();
    }

    /** {@code %PDF-} magic bytes, for artifacts whose content type and extension both lie. */
    private static boolean hasPdfMagic(byte[] content) {
        return content.length >= 5
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D'
                && content[3] == 'F' && content[4] == '-';
    }
}
