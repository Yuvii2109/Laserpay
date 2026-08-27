package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.time.Clocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Customer emails are first-class evidence, so the envelope fields have to survive extraction as
 * structured metadata - not just as text somewhere in the body.
 */
class EmlExtractorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:15:30Z");

    private final EmlExtractor extractor = new EmlExtractor(Clocks.fixed(NOW));

    @Test
    @DisplayName("extracts from/to/date/subject as metadata and prepends them to the search text")
    void extractsHeadersAndBody() {
        ExtractionRequest request = ExtractionRequest.of(
                "MER-1/TX-9/CUSTOMER_COMMUNICATION/EV-7/v1/reply.eml",
                "reply.eml", "message/rfc822", TestDocuments.simpleEml());

        assertThat(extractor.supports(request)).isTrue();
        ExtractionResult result = extractor.extract(request);

        assertThat(result.extractor()).isEqualTo("eml");
        assertThat(result.metadata())
                .containsEntry("eml.from", "Priya Raman <priya.raman@example.com>")
                .containsEntry("eml.to", "support@northwind-traders.example")
                .containsEntry("eml.cc", "billing@northwind-traders.example")
                .containsEntry("eml.subject", "Re: Order ORD-88213 delivery confirmation")
                .containsEntry("eml.messageId", "<8821-3f9a@example.com>")
                .containsEntry("eml.bodyContentType", "text/plain; charset=utf-8");

        // Date normalised to ISO-8601 UTC; the platform never stores a local date-time.
        assertThat(result.metadata().get("eml.date"))
                .isEqualTo(Instant.parse("2026-08-18T09:14:22Z").toString());

        assertThat(result.text())
                .contains("From: Priya Raman")
                .contains("Subject: Re: Order ORD-88213 delivery confirmation")
                .contains("arrived on 17 August 2026");
        assertThat(result.pageCount()).isZero();
    }

    @Test
    @DisplayName("decodes RFC 2047 subjects, quoted-printable bodies and lists attachments")
    void handlesMultipartWithEncodedHeaders() {
        ExtractionResult result = extractor.extract(ExtractionRequest.of(
                "k/refund.eml", "refund.eml", "message/rfc822", TestDocuments.multipartEml()));

        assertThat(result.metadata())
                .containsEntry("eml.from", "Renée Dubois <renee@example.fr>")
                .containsEntry("eml.subject", "Refund request - order ORD-90012")
                .containsEntry("eml.attachmentCount", "1")
                .containsEntry("eml.attachmentNames", "cancellation-receipt.pdf")
                .containsEntry("eml.bodyContentType", "text/plain");

        // The quoted-printable soft line break must not leave "on=" glued to the next line.
        assertThat(result.text()).contains("renewal was charged on 3 August");
        // text/plain wins over the HTML alternative.
        assertThat(result.text()).doesNotContain("HTML alternative body");
        // Attachment bytes are never inlined: the attachment gets its own evidence record.
        assertThat(result.text()).doesNotContain("JVBERi0xLjQK");
    }

    @Test
    @DisplayName("rejects bytes that are not an RFC 5322 message")
    void nonEmailFails() {
        ExtractionRequest request = ExtractionRequest.of("k/x.eml", "x.eml", "message/rfc822",
                "no headers, no blank line".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor.extract(request))
                .isInstanceOf(ExtractionFailedException.class)
                .hasMessageContaining("RFC 5322");
    }

    @Test
    @DisplayName("strips HTML when there is no plain-text alternative")
    void stripsHtml() {
        assertThat(EmlExtractor.stripHtml("<p>Delivered <b>17 Aug</b></p><script>x()</script>"))
                .contains("Delivered")
                .contains("17 Aug")
                .doesNotContain("<b>")
                .doesNotContain("x()");
    }
}
