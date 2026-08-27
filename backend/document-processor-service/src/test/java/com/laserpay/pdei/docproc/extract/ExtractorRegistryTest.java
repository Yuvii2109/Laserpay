package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.time.Clocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Selection must be deterministic and specific-first: the wrong extractor turns a corrupt PDF
 * into a mystery text file, and evidence provenance cannot depend on which parser happened not
 * to throw.
 */
class ExtractorRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:15:30Z");
    private static final Clocks CLOCK = Clocks.fixed(NOW);

    private final ExtractorRegistry registry = new ExtractorRegistry(List.of(
            new PdfBoxDocumentExtractor(CLOCK, 500),
            new EmlExtractor(CLOCK),
            new PlainTextExtractor(CLOCK),
            new TikaDocumentExtractor(CLOCK, 600_000)));

    @Test
    @DisplayName("routes each content type to its dedicated extractor, Tika last")
    void routesByContentType() {
        assertThat(select("pod.pdf", "application/pdf", "%PDF-1.4")).isEqualTo("pdfbox");
        assertThat(select("reply.eml", "message/rfc822", "From: a@b\r\n\r\nhi")).isEqualTo("eml");
        assertThat(select("order.json", "application/json", "{}")).isEqualTo("plaintext");
        assertThat(select("manifest.csv", "text/csv", "a,b")).isEqualTo("plaintext");
        assertThat(select("policy.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "PK"))
                .isEqualTo("tika");
        assertThat(select("signature.png", "image/png", "x")).isEqualTo("tika");
    }

    @Test
    @DisplayName("names() reports selection order, which is the contract of this class")
    void reportsSelectionOrder() {
        assertThat(registry.names()).containsExactly("pdfbox", "eml", "plaintext", "tika");
        assertThat(registry.byName("pdfbox")).isPresent();
        assertThat(registry.byName("nope")).isEmpty();
    }

    @Test
    @DisplayName("plain text is decoded and normalised end to end")
    void extractsPlainText() {
        ExtractionResult result = registry.extract(ExtractionRequest.of("k/order.txt", "order.txt",
                "text/plain", "ORDER ORD-1\n\n\n\nAmount  1299900 INR".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.extractor()).isEqualTo("plaintext");
        assertThat(result.text()).isEqualTo("ORDER ORD-1\n\nAmount 1299900 INR");
        assertThat(result.metadata()).containsEntry("text.lineCount", "3");
    }

    @Test
    @DisplayName("a registry without the catch-all refuses rather than guessing")
    void refusesWhenNothingClaims() {
        ExtractorRegistry narrow = new ExtractorRegistry(List.of(new EmlExtractor(CLOCK)));

        assertThatThrownBy(() -> narrow.select(
                ExtractionRequest.of("k/x.bin", "x.bin", "application/octet-stream", new byte[]{1})))
                .isInstanceOf(ExtractionFailedException.class)
                .hasMessageContaining("no extractor claims");
    }

    @Test
    @DisplayName("duplicate extractor names are a configuration error, caught at construction")
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> new ExtractorRegistry(
                List.of(new EmlExtractor(CLOCK), new EmlExtractor(CLOCK))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate extractor name");
    }

    private String select(String filename, String contentType, String body) {
        return registry.select(ExtractionRequest.of("key/" + filename, filename, contentType,
                body.getBytes(StandardCharsets.UTF_8))).name();
    }
}
