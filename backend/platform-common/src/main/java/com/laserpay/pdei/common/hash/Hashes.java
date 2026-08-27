package com.laserpay.pdei.common.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * SHA-256 helpers (docs/SHARED-LIBRARY-API.md section 1.6).
 *
 * <p>Two distinct uses across the platform:
 * <ul>
 *   <li><strong>Content integrity</strong> - every evidence artifact stored in MinIO carries
 *       {@code x-amz-meta-sha256} produced by {@link #sha256(InputStream)}; re-hashing on read is
 *       how tampering is detected (reference section 12).</li>
 *   <li><strong>Tamper-evident chaining</strong> - {@code audit_events} forms a hash chain via
 *       {@link #chain(String, String)}, so any retroactive edit breaks every subsequent link and
 *       {@code GET /audit/verify-chain} reports the first divergence.</li>
 * </ul>
 *
 * <p>All outputs are lowercase hex, 64 characters.
 */
public final class Hashes {

    /** Chain seed used when an entity has no predecessor: 64 zeros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final String ALGORITHM = "SHA-256";
    private static final int STREAM_BUFFER_BYTES = 8192;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    public static String sha256(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        return toHex(newDigest().digest(data));
    }

    /**
     * Streams the input; never buffers the whole artifact in memory, so a 200 MB evidence PDF costs
     * 8 KB of heap to verify.
     *
     * <p>Does <strong>not</strong> close {@code in}: the caller owns the stream (typically a MinIO
     * object response it must close itself).
     */
    public static String sha256(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[STREAM_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return toHex(digest.digest());
    }

    public static String sha256Hex(String s) {
        Objects.requireNonNull(s, "s must not be null");
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hash of the canonical (key-sorted, whitespace-free) JSON form of an object. Stable across
     * services, JVM versions and field declaration order.
     */
    public static String canonicalJsonSha256(Object o, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        try {
            JsonNode tree = mapper.valueToTree(o);
            return sha256Hex(Json.canonical(tree));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Failed to canonicalise object for hashing", e);
        }
    }

    /** Same as {@link #canonicalJsonSha256(Object, ObjectMapper)} using the shared mapper. */
    public static String canonicalJsonSha256(Object o) {
        return canonicalJsonSha256(o, Json.mapper());
    }

    /**
     * One link of a tamper-evident chain: {@code sha256(previousHash || payloadHash)}.
     *
     * <p>A null or blank {@code previousHash} is normalised to {@link #GENESIS_HASH}, so the first
     * link of a chain is well defined and reproducible. Deterministic and order-sensitive:
     * {@code chain(a, b) != chain(b, a)}.
     */
    public static String chain(String previousHash, String payloadHash) {
        Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        String previous = (previousHash == null || previousHash.isBlank())
                ? GENESIS_HASH
                : previousHash;
        return sha256Hex(previous + payloadHash);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform; unreachable on any supported JRE.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
