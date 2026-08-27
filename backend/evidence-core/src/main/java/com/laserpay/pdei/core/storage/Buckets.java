package com.laserpay.pdei.core.storage;

import com.laserpay.pdei.common.domain.EvidenceType;

import java.util.Locale;

/**
 * MinIO bucket names, object key layout and user-metadata keys (platform contract 11).
 *
 * <pre>
 * pdei-evidence  {merchantId}/{transactionId}/{evidenceType}/{evidenceId}/v{version}/{filename}
 * pdei-packages  {merchantId}/{caseId}/representment-{caseId}-v{n}.zip
 *                {merchantId}/{caseId}/manifest.json
 * </pre>
 */
public final class Buckets {

    public static final String EVIDENCE = "pdei-evidence";
    public static final String PACKAGES = "pdei-packages";

    /**
     * User-metadata keys. The MinIO SDK prefixes user metadata with {@code x-amz-meta-}, so the
     * short names below are what we hand to {@code userMetadata(...)} and the
     * {@code X_AMZ_META_*} constants are what actually lands on the object.
     */
    public static final String META_SHA256 = "sha256";
    public static final String META_SOURCE_EVENT_ID = "source-event-id";
    public static final String META_EVIDENCE_ID = "evidence-id";
    public static final String META_VERSION = "version";

    public static final String X_AMZ_META_PREFIX = "x-amz-meta-";
    public static final String X_AMZ_META_SHA256 = X_AMZ_META_PREFIX + META_SHA256;
    public static final String X_AMZ_META_SOURCE_EVENT_ID = X_AMZ_META_PREFIX + META_SOURCE_EVENT_ID;
    public static final String X_AMZ_META_EVIDENCE_ID = X_AMZ_META_PREFIX + META_EVIDENCE_ID;
    public static final String X_AMZ_META_VERSION = X_AMZ_META_PREFIX + META_VERSION;

    private Buckets() {
    }

    /** {@code {merchantId}/{transactionId}/{evidenceType}/{evidenceId}/v{version}/{filename}} */
    public static String evidenceKey(String merchantId, String transactionId, EvidenceType type,
                                     String evidenceId, int version, String filename) {
        return String.join("/",
                segment(merchantId),
                segment(transactionId),
                type == null ? "UNKNOWN" : type.name(),
                segment(evidenceId),
                "v" + Math.max(1, version),
                safeFilename(filename));
    }

    /** {@code {merchantId}/{caseId}/representment-{caseId}-v{n}.zip} */
    public static String packageBundleKey(String merchantId, String caseId, int packageVersion) {
        return segment(merchantId) + "/" + segment(caseId)
                + "/representment-" + segment(caseId) + "-v" + Math.max(1, packageVersion) + ".zip";
    }

    /** {@code {merchantId}/{caseId}/manifest.json} */
    public static String packageManifestKey(String merchantId, String caseId) {
        return segment(merchantId) + "/" + segment(caseId) + "/manifest.json";
    }

    /** Normalise a path segment: never empty, never containing a separator. */
    private static String segment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.trim().replace('/', '_').replace('\\', '_');
    }

    /**
     * Keep the original filename recognisable (it appears in the representment bundle) while
     * stripping anything that could escape the key prefix.
     */
    public static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "artifact.bin";
        }
        String name = filename.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "artifact.bin";
        }
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }

    /** Best-effort content type from a filename, used when the uploader does not send one. */
    public static String contentTypeFor(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".csv")) {
            return "text/csv";
        }
        if (name.endsWith(".txt") || name.endsWith(".log")) {
            return "text/plain";
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "text/html";
        }
        if (name.endsWith(".eml")) {
            return "message/rfc822";
        }
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }
}
