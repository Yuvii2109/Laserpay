package com.laserpay.pdei.docproc.extract;

import com.laserpay.pdei.core.storage.Buckets;

import java.util.Locale;
import java.util.Objects;

/**
 * One unit of work for a {@link DocumentExtractor}: the raw bytes plus everything known about
 * them before parsing starts.
 *
 * <p>The bytes are carried in memory on purpose. Evidence artifacts in PDEI are receipts,
 * invoices, delivery notes and emails - kilobytes, not gigabytes - and the size ceiling is
 * enforced before the request is ever built ({@code pdei.docproc.max-object-bytes}). Holding the
 * bytes lets every extractor compute the sha256 over exactly what it parsed, which is the point
 * of the integrity chain.
 *
 * @param objectKey   MinIO key the bytes came from, or a synthetic key for direct uploads
 * @param filename    original filename; drives extension-based extractor selection
 * @param contentType declared MIME type, possibly null or wrong (senders lie)
 * @param content     the artifact bytes
 * @param evidenceId  evidence this artifact belongs to, null for ad-hoc extraction
 */
public record ExtractionRequest(String objectKey,
                                String filename,
                                String contentType,
                                byte[] content,
                                String evidenceId) {

    public ExtractionRequest {
        Objects.requireNonNull(content, "content must not be null");
        if (filename == null || filename.isBlank()) {
            filename = deriveFilename(objectKey);
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = Buckets.contentTypeFor(filename);
        }
    }

    /** Convenience factory for the {@code POST /docproc/v1/extract} path. */
    public static ExtractionRequest of(String objectKey, String filename, String contentType, byte[] content) {
        return new ExtractionRequest(objectKey, filename, contentType, content, null);
    }

    public int size() {
        return content.length;
    }

    /**
     * Lower-cased content type with the parameters stripped, e.g. {@code text/plain} from
     * {@code text/plain; charset=utf-8}.
     */
    public String baseContentType() {
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /** Lower-cased filename extension without the dot, or the empty string when there is none. */
    public String extension() {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String deriveFilename(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "artifact.bin";
        }
        int slash = objectKey.lastIndexOf('/');
        String tail = slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
        return tail.isBlank() ? "artifact.bin" : tail;
    }
}
