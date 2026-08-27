package com.laserpay.pdei.docproc.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Builds the documents the extraction tests parse.
 *
 * <p>Generated rather than checked in as fixtures: a committed binary PDF is opaque in review,
 * drifts from the code that reads it, and hides exactly the thing under test (which text is on
 * which page). Generating it means the assertion and the input sit next to each other.
 */
final class TestDocuments {

    private TestDocuments() {
    }

    /**
     * A delivery-proof-shaped PDF with one page per entry in {@code pageTexts} and a populated
     * document information dictionary.
     */
    static byte[] pdf(String title, String author, Instant createdAt, List<String> pageTexts) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            document.getDocumentInformation().setTitle(title);
            document.getDocumentInformation().setAuthor(author);
            document.getDocumentInformation().setProducer("pdei-test-fixture");
            document.getDocumentInformation().setSubject("Proof of delivery");
            if (createdAt != null) {
                Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                calendar.setTimeInMillis(createdAt.toEpochMilli());
                document.getDocumentInformation().setCreationDate(calendar);
            }

            for (String pageText : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f);
                    content.newLineAtOffset(60f, 740f);
                    for (String line : pageText.split("\n")) {
                        content.showText(line);
                        content.newLineAtOffset(0f, -18f);
                    }
                    content.endText();
                }
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A plain-text {@code .eml} customer email. */
    static byte[] simpleEml() {
        String eml = """
                From: Priya Raman <priya.raman@example.com>
                To: support@northwind-traders.example
                Cc: billing@northwind-traders.example
                Date: Tue, 18 Aug 2026 09:14:22 +0000
                Subject: Re: Order ORD-88213 delivery confirmation
                Message-ID: <8821-3f9a@example.com>
                Content-Type: text/plain; charset=utf-8
                Content-Transfer-Encoding: 7bit

                Hello,

                Confirming that the parcel for order ORD-88213 arrived on 17 August 2026
                and was signed for by me at the front desk. No further action needed.

                Regards,
                Priya
                """.replace("\n", "\r\n");
        return eml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A multipart email with an RFC 2047 encoded subject, a quoted-printable text part, an HTML
     * alternative and a named attachment.
     */
    static byte[] multipartEml() {
        String eml = """
                From: =?utf-8?q?Ren=C3=A9e_Dubois?= <renee@example.fr>
                To: disputes@northwind-traders.example
                Date: Wed, 19 Aug 2026 16:05:00 +0200
                Subject: =?utf-8?B?UmVmdW5kIHJlcXVlc3QgLSBvcmRlciBPUkQtOTAwMTI=?=
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="pdei-boundary-1"

                --pdei-boundary-1
                Content-Type: text/plain; charset=utf-8
                Content-Transfer-Encoding: quoted-printable

                I cancelled the subscription on 1 August, but the renewal was charged on=
                 3 August. Please refund order ORD-90012.

                --pdei-boundary-1
                Content-Type: text/html; charset=utf-8

                <html><body><p>HTML alternative body</p></body></html>

                --pdei-boundary-1
                Content-Type: application/pdf; name="cancellation-receipt.pdf"
                Content-Disposition: attachment; filename="cancellation-receipt.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQK

                --pdei-boundary-1--
                """.replace("\n", "\r\n");
        return eml.getBytes(StandardCharsets.UTF_8);
    }
}
