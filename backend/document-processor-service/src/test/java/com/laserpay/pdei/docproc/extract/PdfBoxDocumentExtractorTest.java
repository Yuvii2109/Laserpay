package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extraction of a generated PDF: page count, per-page text, embedded metadata and the sha256 over
 * exactly the parsed bytes.
 */
class PdfBoxDocumentExtractorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:15:30Z");
    private static final Instant CREATED = Instant.parse("2026-08-17T08:00:00Z");

    private final PdfBoxDocumentExtractor extractor =
            new PdfBoxDocumentExtractor(Clocks.fixed(NOW), 500);

    @Test
    @DisplayName("extracts text, page count and document information from a generated PDF")
    void extractsGeneratedPdf() {
        byte[] pdf = TestDocuments.pdf("Proof of delivery ORD-88213", "Northwind Logistics", CREATED,
                List.of("PROOF OF DELIVERY\nOrder ORD-88213\nCarrier Northwind Logistics",
                        "Delivered 2026-08-17T14:32:00Z\nSigned by P Raman\nAddress 14 Residency Road"));

        ExtractionRequest request = ExtractionRequest.of(
                "MER-1/TX-9/DELIVERY_PROOF/EV-3/v1/pod.pdf", "pod.pdf", "application/pdf", pdf);

        assertThat(extractor.supports(request)).isTrue();
        ExtractionResult result = extractor.extract(request);

        assertThat(result.extractor()).isEqualTo("pdfbox");
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.pages()).hasSize(2);
        assertThat(result.pages().get(0)).contains("PROOF OF DELIVERY").contains("ORD-88213");
        assertThat(result.pages().get(1)).contains("Signed by P Raman");
        assertThat(result.text()).contains("ORD-88213").contains("2026-08-17T14:32:00Z");
        assertThat(result.sha256()).isEqualTo(Hashes.sha256(pdf));
        assertThat(result.sizeBytes()).isEqualTo(pdf.length);
        assertThat(result.extractedAt()).isEqualTo(NOW);
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("carries the document information dictionary into metadata as provenance")
    void capturesEmbeddedMetadata() {
        byte[] pdf = TestDocuments.pdf("Invoice INV-4021", "Northwind Traders", CREATED,
                List.of("INVOICE INV-4021\nAmount 12999.00 INR"));

        ExtractionResult result = extractor.extract(
                ExtractionRequest.of("k/invoice.pdf", "invoice.pdf", "application/pdf", pdf));

        assertThat(result.metadata())
                .containsEntry("pdf.title", "Invoice INV-4021")
                .containsEntry("pdf.author", "Northwind Traders")
                .containsEntry("pdf.producer", "pdei-test-fixture")
                .containsEntry("pdf.pageCount", "1");
        // PDF dates are Calendar; the platform stores ISO-8601 UTC and nothing else.
        assertThat(result.metadata().get("pdf.creationDate")).isEqualTo(CREATED.toString());
    }

    @Test
    @DisplayName("selects on magic bytes when the content type and the extension both lie")
    void detectsPdfByMagicBytes() {
        byte[] pdf = TestDocuments.pdf("t", "a", CREATED, List.of("hello"));
        ExtractionRequest mislabelled =
                ExtractionRequest.of("k/receipt", "receipt", "application/octet-stream", pdf);

        assertThat(extractor.supports(mislabelled)).isTrue();
        assertThat(extractor.extract(mislabelled).text()).contains("hello");
    }

    @Test
    @DisplayName("a PDF with no readable text layer reports the no-OCR gap instead of failing")
    void emptyPdfWarnsAboutMissingTextLayer() {
        byte[] pdf = TestDocuments.pdf("scan", "scanner", CREATED, List.of(""));

        ExtractionResult result = extractor.extract(
                ExtractionRequest.of("k/scan.pdf", "scan.pdf", "application/pdf", pdf));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.warnings()).contains(PdfBoxDocumentExtractor.WARNING_NO_TEXT_LAYER);
    }

    @Test
    @DisplayName("undecodable bytes raise ExtractionFailedException, which routes to quarantine")
    void undecodableBytesFail() {
        byte[] garbage = "%PDF-1.4 this is not actually a pdf".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(
                ExtractionRequest.of("k/broken.pdf", "broken.pdf", "application/pdf", garbage)))
                .isInstanceOf(ExtractionFailedException.class)
                .hasMessageContaining("broken.pdf");
    }

    @Test
    @DisplayName("respects the page ceiling and says so")
    void honoursPageLimit() {
        PdfBoxDocumentExtractor limited = new PdfBoxDocumentExtractor(Clocks.fixed(NOW), 1);
        byte[] pdf = TestDocuments.pdf("t", "a", CREATED, List.of("page one", "page two"));

        ExtractionResult result = limited.extract(
                ExtractionRequest.of("k/two.pdf", "two.pdf", "application/pdf", pdf));

        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.pages()).hasSize(1);
        assertThat(result.text()).contains("page one").doesNotContain("page two");
        assertThat(result.warnings()).anyMatch(w -> w.contains("page limit reached"));
    }
}
