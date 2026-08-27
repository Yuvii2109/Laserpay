package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Locale;

/**
 * Direct reader for text formats: {@code text/*}, {@code application/json}, {@code application/xml}.
 *
 * <p>These are the bulk of PDEI's evidence in a simulated world - structured receipts, order
 * records, CSV shipment manifests, JSON PSP payloads - and routing them through the full Tika
 * auto-detect pipeline buys nothing but latency and a stack of format-detection metadata nobody
 * reads. Decoding here is also stricter: bytes that are not valid UTF-8 are reported rather than
 * silently replaced with U+FFFD, which is how a truncated upload would otherwise reach the FTS
 * index looking perfectly healthy.
 */
public class PlainTextExtractor implements DocumentExtractor {

    public static final String NAME = "plaintext";

    private final Clocks clock;

    public PlainTextExtractor(Clocks clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(ExtractionRequest request) {
        String type = request.baseContentType();
        if (type.startsWith("text/") && !type.startsWith("text/html")) {
            return true;
        }
        if ("application/json".equals(type) || "application/xml".equals(type)
                || "application/x-ndjson".equals(type)) {
            return true;
        }
        String extension = request.extension();
        return switch (extension) {
            case "txt", "log", "csv", "tsv", "json", "ndjson", "xml", "md" -> true;
            default -> false;
        };
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        ExtractionResult.Builder result = ExtractionResult.builder()
                .extractor(NAME)
                .contentType(request.contentType())
                .sizeBytes(request.size())
                .sha256(Hashes.sha256(request.content()))
                .extractedAt(clock.now())
                .pageCount(0);

        String decoded = decodeStrictUtf8(request.content(), result);
        String text = TextNormalizer.normalize(decoded);
        result.text(text);
        result.meta("text.lineCount", Integer.toString(countLines(text)));
        result.meta("text.encoding", "UTF-8");
        result.meta("text.format", request.extension().isEmpty()
                ? request.baseContentType()
                : request.extension().toUpperCase(Locale.ROOT));
        if (text.isBlank()) {
            result.warn("text artifact is empty");
        }
        return result.build();
    }

    /**
     * Strict UTF-8 decode with a lenient second pass. The first pass is what detects a corrupt or
     * truncated upload; the second pass is what still gets the readable prefix into the evidence
     * record instead of throwing the whole artifact away.
     */
    private static String decodeStrictUtf8(byte[] content, ExtractionResult.Builder result) {
        try {
            CharBuffer buffer = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return buffer.toString();
        } catch (CharacterCodingException e) {
            result.warn("not valid UTF-8; decoded leniently with replacement characters");
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
