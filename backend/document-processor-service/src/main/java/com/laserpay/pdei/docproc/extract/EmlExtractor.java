package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.time.Clocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reader for {@code .eml} customer emails (RFC 5322 / MIME).
 *
 * <p>Customer communication is first-class evidence in PDEI: a
 * {@code CUSTOMER_COMMUNICATION} artifact showing the customer acknowledging delivery, or
 * requesting cancellation two days after the renewal charge, is frequently the difference
 * between DEFENDABLE and AMBIGUOUS. So the email is not flattened to a body string - the
 * envelope fields (from, to, date, subject, message-id) are extracted as first-class metadata
 * and also prepended to the FTS text, because "who said it and when" is what an investigator
 * searches for.
 *
 * <p>Implemented directly against the RFC rather than pulling in Jakarta Mail: the subset that
 * matters here (unfolding, encoded-words, a single level of multipart, quoted-printable and
 * base64 transfer encodings) is small, and a mail session object model would drag a
 * connection-oriented API into a service that never connects to anything.
 *
 * <p>Attachments are enumerated, not decoded. An attached PDF gets its own evidence record and
 * therefore its own extraction pass through {@link PdfBoxDocumentExtractor}; decoding it here
 * would duplicate text into two FTS documents and break the "one artifact, one sha256" rule.
 */
public class EmlExtractor implements DocumentExtractor {

    public static final String NAME = "eml";

    private static final Logger log = LoggerFactory.getLogger(EmlExtractor.class);

    private static final String MESSAGE_RFC822 = "message/rfc822";
    private static final int MAX_MULTIPART_PARTS = 64;

    /** RFC 2047 encoded-word: {@code =?charset?B|Q?text?=}. */
    private static final Pattern ENCODED_WORD =
            Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");
    private static final Pattern BOUNDARY_PARAM =
            Pattern.compile("boundary\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_PARAM =
            Pattern.compile("charset\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PARAM =
            Pattern.compile("(?:file)?name\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** Date formats seen in the wild, tried in order. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm Z", Locale.ENGLISH));

    private final Clocks clock;

    public EmlExtractor(Clocks clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(ExtractionRequest request) {
        return MESSAGE_RFC822.equals(request.baseContentType())
                || "eml".equals(request.extension())
                || ("msg".equals(request.extension()) && looksLikeRfc822(request.content()));
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        ExtractionResult.Builder result = ExtractionResult.builder()
                .extractor(NAME)
                .contentType(MESSAGE_RFC822)
                .sizeBytes(request.size())
                .sha256(Hashes.sha256(request.content()))
                .extractedAt(clock.now())
                .pageCount(0);

        // Headers are US-ASCII by definition; the body charset comes from Content-Type.
        String raw = new String(request.content(), StandardCharsets.ISO_8859_1);
        int headerEnd = findHeaderEnd(raw);
        if (headerEnd < 0) {
            throw new ExtractionFailedException(NAME,
                    "not an RFC 5322 message (no header/body separator): " + request.objectKey());
        }

        Map<String, String> headers = parseHeaders(raw.substring(0, headerEnd));
        String rawBody = raw.substring(headerEnd);

        String from = decodeWords(headers.get("from"));
        String to = decodeWords(headers.get("to"));
        String cc = decodeWords(headers.get("cc"));
        String subject = decodeWords(headers.get("subject"));
        String dateHeader = headers.get("date");

        result.meta("eml.from", from);
        result.meta("eml.to", to);
        result.meta("eml.cc", cc);
        result.meta("eml.subject", subject);
        result.meta("eml.messageId", headers.get("message-id"));
        result.meta("eml.inReplyTo", headers.get("in-reply-to"));
        result.meta("eml.dateHeader", dateHeader);

        Instant sentAt = parseDate(dateHeader);
        if (sentAt != null) {
            // ISO-8601 UTC, never a local date-time (platform contract 5).
            result.meta("eml.date", sentAt.toString());
        } else if (dateHeader != null && !dateHeader.isBlank()) {
            result.warn("unparseable Date header: " + dateHeader);
        }

        Body body = readBody(headers, rawBody, result);
        result.meta("eml.bodyContentType", body.contentType());

        String text = TextNormalizer.normalize(renderForSearch(from, to, cc, subject,
                sentAt, dateHeader, body.text()));
        result.text(text);
        if (text.isBlank()) {
            result.warn("email had no readable text body");
        }
        return result.build();
    }

    // ---------------------------------------------------------------------------------------
    // Header parsing
    // ---------------------------------------------------------------------------------------

    /** Index just past the blank line that separates headers from body, or -1. */
    private static int findHeaderEnd(String raw) {
        int crlf = raw.indexOf("\r\n\r\n");
        if (crlf >= 0) {
            return crlf + 4;
        }
        int lf = raw.indexOf("\n\n");
        return lf >= 0 ? lf + 2 : -1;
    }

    /**
     * Unfolds continuation lines and lower-cases field names. Later duplicates of a field are
     * kept only when the field is one that legitimately repeats (Received); for everything else
     * the first occurrence wins, which is what RFC 5322 says a reader should do.
     */
    private static Map<String, String> parseHeaders(String headerBlock) {
        Map<String, String> headers = new LinkedHashMap<>();
        String current = null;
        StringBuilder value = new StringBuilder();

        for (String line : headerBlock.split("\\R")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (current != null) {
                    value.append(' ').append(line.strip());
                }
                continue;
            }
            flushHeader(headers, current, value);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                current = null;
                continue;
            }
            current = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            value.setLength(0);
            value.append(line.substring(colon + 1).strip());
        }
        flushHeader(headers, current, value);
        return headers;
    }

    private static void flushHeader(Map<String, String> headers, String name, StringBuilder value) {
        if (name != null && !headers.containsKey(name)) {
            headers.put(name, value.toString());
        }
    }

    /** RFC 2047 encoded-word decoding, applied to human-readable header fields. */
    static String decodeWords(String header) {
        if (header == null || header.isBlank()) {
            return header;
        }
        Matcher matcher = ENCODED_WORD.matcher(header);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String decoded;
            try {
                Charset charset = charsetOrDefault(matcher.group(1));
                String encoding = matcher.group(2).toUpperCase(Locale.ROOT);
                String payload = matcher.group(3);
                byte[] bytes = "B".equals(encoding)
                        ? Base64.getMimeDecoder().decode(payload)
                        : decodeQuotedPrintable(payload.replace('_', ' '), true);
                decoded = new String(bytes, charset);
            } catch (RuntimeException e) {
                decoded = matcher.group(0); // undecodable: keep the raw encoded-word
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Instant parseDate(String dateHeader) {
        if (dateHeader == null || dateHeader.isBlank()) {
            return null;
        }
        // Strip a trailing "(GMT)"-style comment, which the strict formatters reject.
        String cleaned = dateHeader.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(cleaned, format).toInstant();
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Body parsing
    // ---------------------------------------------------------------------------------------

    /** The chosen body part: its decoded text and the content type it came from. */
    private record Body(String text, String contentType) {
    }

    private Body readBody(Map<String, String> headers, String rawBody, ExtractionResult.Builder result) {
        String contentType = headers.getOrDefault("content-type", "text/plain");
        String transferEncoding = headers.getOrDefault("content-transfer-encoding", "7bit");

        if (!contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return new Body(decodePart(rawBody, transferEncoding, contentType, result), contentType);
        }

        Matcher boundaryMatch = BOUNDARY_PARAM.matcher(contentType);
        if (!boundaryMatch.find()) {
            result.warn("multipart message without a boundary parameter; read as plain text");
            return new Body(decodePart(rawBody, transferEncoding, contentType, result), contentType);
        }
        return readMultipart(rawBody, boundaryMatch.group(1).strip(), result);
    }

    /**
     * One level of multipart. Prefers {@code text/plain}, falls back to {@code text/html} with
     * tags stripped, and records anything with a filename as an attachment.
     *
     * <p>Nested multiparts (multipart/mixed containing multipart/alternative) are handled by
     * treating an inner boundary as an unreadable part and recording a warning; the common
     * shapes produced by real mail clients put the text part at the first level.
     */
    private Body readMultipart(String rawBody, String boundary, ExtractionResult.Builder result) {
        String[] parts = rawBody.split(Pattern.quote("--" + boundary));
        String plain = null;
        String html = null;
        List<String> attachments = new ArrayList<>();

        int examined = 0;
        for (String part : parts) {
            if (++examined > MAX_MULTIPART_PARTS) {
                result.warn("multipart limit reached at " + MAX_MULTIPART_PARTS + " parts");
                break;
            }
            String trimmed = part.strip();
            if (trimmed.isEmpty() || "--".equals(trimmed)) {
                continue;
            }
            int headerEnd = findHeaderEnd(part);
            if (headerEnd < 0) {
                continue;
            }
            Map<String, String> partHeaders = parseHeaders(part.substring(0, headerEnd));
            String partBody = part.substring(headerEnd);
            String partType = partHeaders.getOrDefault("content-type", "text/plain")
                    .toLowerCase(Locale.ROOT);
            String disposition = partHeaders.getOrDefault("content-disposition", "");
            String encoding = partHeaders.getOrDefault("content-transfer-encoding", "7bit");

            String filename = filenameOf(disposition, partHeaders.get("content-type"));
            if (filename != null) {
                attachments.add(filename);
                continue;
            }
            if (partType.startsWith("text/plain") && plain == null) {
                plain = decodePart(partBody, encoding, partType, result);
            } else if (partType.startsWith("text/html") && html == null) {
                html = stripHtml(decodePart(partBody, encoding, partType, result));
            } else if (partType.startsWith("multipart/")) {
                result.warn("nested multipart part skipped: " + partType);
            }
        }

        if (!attachments.isEmpty()) {
            result.meta("eml.attachmentCount", Integer.toString(attachments.size()));
            result.meta("eml.attachmentNames", String.join(", ", attachments));
        }
        if (plain != null) {
            return new Body(plain, "text/plain");
        }
        if (html != null) {
            return new Body(html, "text/html");
        }
        result.warn("multipart message had no text/plain or text/html part");
        return new Body("", "multipart");
    }

    private String decodePart(String body, String transferEncoding, String contentType,
                              ExtractionResult.Builder result) {
        String encoding = transferEncoding == null ? "7bit" : transferEncoding.strip().toLowerCase(Locale.ROOT);
        Charset charset = charsetOf(contentType);
        try {
            return switch (encoding) {
                case "base64" -> new String(Base64.getMimeDecoder().decode(body), charset);
                case "quoted-printable" -> new String(decodeQuotedPrintable(body, false), charset);
                default -> new String(body.getBytes(StandardCharsets.ISO_8859_1), charset);
            };
        } catch (RuntimeException e) {
            log.warn("email part with encoding {} could not be decoded: {}", encoding, e.toString());
            result.warn("undecodable body part (" + encoding + "): " + e.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * RFC 2045 quoted-printable.
     *
     * @param headerMode {@code true} for encoded-words, where soft line breaks do not occur
     */
    static byte[] decodeQuotedPrintable(String input, boolean headerMode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != '=') {
                if (c == '\r' && !headerMode) {
                    continue; // normalised below by the caller
                }
                out.write(c);
                continue;
            }
            if (i + 1 < input.length() && !headerMode
                    && (input.charAt(i + 1) == '\n' || input.charAt(i + 1) == '\r')) {
                // Soft line break: "=\r\n" or "=\n" continues the logical line.
                boolean crlf = input.charAt(i + 1) == '\r'
                        && i + 2 < input.length() && input.charAt(i + 2) == '\n';
                i += crlf ? 2 : 1;
                continue;
            }
            if (i + 2 >= input.length()) {
                out.write(c);
                continue;
            }
            int value = hexValue(input.charAt(i + 1)) << 4 | hexValue(input.charAt(i + 2));
            if (value < 0) {
                out.write(c);
                continue;
            }
            out.write(value);
            i += 2;
        }
        return out.toByteArray();
    }

    private static int hexValue(char c) {
        int digit = Character.digit(c, 16);
        return digit < 0 ? -1 : digit;
    }

    private static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = CHARSET_PARAM.matcher(contentType);
        return matcher.find() ? charsetOrDefault(matcher.group(1)) : StandardCharsets.UTF_8;
    }

    private static Charset charsetOrDefault(String name) {
        try {
            return Charset.forName(name.strip());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException | NullPointerException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String filenameOf(String disposition, String contentType) {
        for (String source : new String[]{disposition, contentType}) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Matcher matcher = FILENAME_PARAM.matcher(source);
            if (matcher.find()) {
                return decodeWords(matcher.group(1).strip());
            }
        }
        return disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment")
                ? "unnamed-attachment"
                : null;
    }

    /** Crude but sufficient HTML-to-text: entities for the four that matter, then tags removed. */
    static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p\\s*>", "\n\n");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
    }

    /**
     * The searchable rendering. Headers are prepended so that a merchant searching for a customer
     * address or a subject line hits the evidence even when the body never repeats them.
     */
    private static String renderForSearch(String from, String to, String cc, String subject,
                                          Instant sentAt, String dateHeader, String body) {
        StringBuilder sb = new StringBuilder(body.length() + 256);
        appendIfPresent(sb, "From", from);
        appendIfPresent(sb, "To", to);
        appendIfPresent(sb, "Cc", cc);
        appendIfPresent(sb, "Date", sentAt != null ? sentAt.toString() : dateHeader);
        appendIfPresent(sb, "Subject", subject);
        sb.append('\n').append(body);
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.strip()).append('\n');
        }
    }

    private static boolean looksLikeRfc822(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, 2048), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.contains("\nfrom:") || head.startsWith("from:")
                || head.contains("\nsubject:") || head.startsWith("subject:");
    }
}
