package com.laserpay.pdei.core.storage;

import java.time.Instant;

/** Result of a successful {@link ObjectStore#put}. */
public record StoredObject(
        String bucket,
        String objectKey,
        String sha256,
        long sizeBytes,
        String contentType,
        String etag,
        String versionId,
        Instant storedAt) {
}
