package com.laserpay.pdei.core.storage;

import java.time.Instant;
import java.util.Map;

/** Metadata of a stored object, as returned by {@link ObjectStore#stat}. */
public record ObjectStat(
        String bucket,
        String objectKey,
        long sizeBytes,
        String contentType,
        String etag,
        String versionId,
        Instant lastModified,
        Map<String, String> userMetadata) {

    public ObjectStat {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    /** The sha256 recorded at upload time, looked up under either the short or the wire key. */
    public String recordedSha256() {
        String value = userMetadata.get(Buckets.META_SHA256);
        if (value == null) {
            value = userMetadata.get(Buckets.X_AMZ_META_SHA256);
        }
        return value;
    }
}
