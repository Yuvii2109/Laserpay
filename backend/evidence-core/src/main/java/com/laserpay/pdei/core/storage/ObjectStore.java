package com.laserpay.pdei.core.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * Content-addressed blob storage for evidence artifacts and representment bundles.
 *
 * <p>Implementations must never overwrite an existing key with different content: new evidence
 * versions get a new key (platform contract 11), and the evidence bucket has object versioning on
 * as a second line of defence.</p>
 */
public interface ObjectStore {

    /**
     * Store bytes and return what was written, including the sha256 the store computed itself.
     *
     * @param bucket       target bucket
     * @param objectKey    full key, built with {@link Buckets}
     * @param content      the bytes to store
     * @param contentType  MIME type, {@code null} to infer from the key
     * @param userMetadata short metadata keys (see {@link Buckets}); the implementation adds the
     *                     {@code x-amz-meta-} prefix
     */
    StoredObject put(String bucket, String objectKey, byte[] content, String contentType,
                     Map<String, String> userMetadata);

    /** Streaming variant for large artifacts where the sha256 is already known. */
    StoredObject put(String bucket, String objectKey, InputStream content, long size, String contentType,
                     Map<String, String> userMetadata);

    /** Read the whole object. Used by integrity verification and package assembly. */
    byte[] getBytes(String bucket, String objectKey);

    /** Open a stream; the caller closes it. */
    InputStream get(String bucket, String objectKey);

    /** Time-limited download URL, served to browsers by {@code GET /evidence/{id}/download}. */
    String presignedGet(String bucket, String objectKey, Duration ttl);

    /** Metadata only, no payload transfer. */
    ObjectStat stat(String bucket, String objectKey);

    boolean exists(String bucket, String objectKey);

    /**
     * Remove an object. Only ever used for orphaned uploads and simulator chaos
     * ({@code ChaosType.DELETE_EVIDENCE}); evidence lifecycle uses status transitions, not deletes.
     */
    void delete(String bucket, String objectKey);

    /** Create the buckets if missing and enable versioning where required. Idempotent. */
    void ensureBuckets(Collection<String> buckets);
}
