package com.laserpay.pdei.core.storage;

import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.core.util.CoreErrors;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketVersioningArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.VersioningConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MinIO-backed {@link ObjectStore}.
 *
 * <p>Every {@code put} computes the sha256 of the bytes it actually wrote and stamps it, together
 * with the source event id, evidence id and version, into user metadata (platform contract 11).
 * That stamp is what {@code EvidenceIntegrityService} re-checks later.</p>
 */
public class MinioObjectStore implements ObjectStore, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStore.class);
    private static final long PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient client;
    private final List<String> managedBuckets;
    private final boolean ensureOnStartup;
    private final boolean versioningEnabled;

    public MinioObjectStore(MinioClient client, List<String> managedBuckets, boolean ensureOnStartup,
                            boolean versioningEnabled) {
        this.client = client;
        this.managedBuckets = managedBuckets == null || managedBuckets.isEmpty()
                ? List.of(Buckets.EVIDENCE, Buckets.PACKAGES)
                : List.copyOf(managedBuckets);
        this.ensureOnStartup = ensureOnStartup;
        this.versioningEnabled = versioningEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (!ensureOnStartup) {
            return;
        }
        try {
            ensureBuckets(managedBuckets);
        } catch (RuntimeException e) {
            // A worker must still boot when MinIO is not up yet; the first put will retry.
            log.warn("could not ensure MinIO buckets on startup: {}", e.toString());
        }
    }

    @Override
    public StoredObject put(String bucket, String objectKey, byte[] content, String contentType,
                            Map<String, String> userMetadata) {
        CoreErrors.requireValue(content, "content");
        String sha256 = Hashes.sha256(content);
        Map<String, String> metadata = withSha(userMetadata, sha256);
        String resolvedType = resolveContentType(contentType, objectKey);
        try (InputStream in = new ByteArrayInputStream(content)) {
            ObjectWriteResponse response = client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, content.length, -1)
                    .contentType(resolvedType)
                    .userMetadata(metadata)
                    .build());
            return new StoredObject(bucket, objectKey, sha256, content.length, resolvedType,
                    response.etag(), response.versionId(), Instant.now());
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "put failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public StoredObject put(String bucket, String objectKey, InputStream content, long size,
                            String contentType, Map<String, String> userMetadata) {
        CoreErrors.requireValue(content, "content");
        String declaredSha = userMetadata == null ? null : userMetadata.get(Buckets.META_SHA256);
        String resolvedType = resolveContentType(contentType, objectKey);
        try {
            ObjectWriteResponse response = client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(content, size < 0 ? -1 : size, size < 0 ? PART_SIZE : -1)
                    .contentType(resolvedType)
                    .userMetadata(userMetadata == null ? Map.of() : new LinkedHashMap<>(userMetadata))
                    .build());
            long written = size >= 0 ? size : stat(bucket, objectKey).sizeBytes();
            return new StoredObject(bucket, objectKey, declaredSha, written, resolvedType,
                    response.etag(), response.versionId(), Instant.now());
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "streaming put failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public byte[] getBytes(String bucket, String objectKey) {
        try (InputStream in = get(bucket, objectKey)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "get failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public InputStream get(String bucket, String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "get failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public String presignedGet(String bucket, String objectKey, Duration ttl) {
        int seconds = (int) Math.max(1L, Math.min(ttl == null ? 900L : ttl.getSeconds(), 7L * 24 * 3600));
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(seconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "presign failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public ObjectStat stat(String bucket, String objectKey) {
        try {
            StatObjectResponse response = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            Map<String, String> metadata = new LinkedHashMap<>();
            if (response.userMetadata() != null) {
                metadata.putAll(response.userMetadata());
            }
            Instant lastModified = response.lastModified() == null
                    ? null : response.lastModified().toInstant();
            return new ObjectStat(bucket, objectKey, response.size(), response.contentType(),
                    response.etag(), response.versionId(), lastModified, metadata);
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "stat failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw CoreErrors.upstream("MinIO", "delete failed for " + bucket + "/" + objectKey + ": " + e);
        }
    }

    @Override
    public void ensureBuckets(Collection<String> buckets) {
        for (String bucket : buckets) {
            try {
                boolean present = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!present) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("created MinIO bucket {}", bucket);
                }
                if (versioningEnabled && Buckets.EVIDENCE.equals(bucket)) {
                    client.setBucketVersioning(SetBucketVersioningArgs.builder()
                            .bucket(bucket)
                            .config(new VersioningConfiguration(
                                    VersioningConfiguration.Status.ENABLED, null))
                            .build());
                }
            } catch (Exception e) {
                throw CoreErrors.upstream("MinIO", "ensureBucket failed for " + bucket + ": " + e);
            }
        }
    }

    private static Map<String, String> withSha(Map<String, String> userMetadata, String sha256) {
        Map<String, String> metadata = new HashMap<>();
        if (userMetadata != null) {
            userMetadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    metadata.put(stripWirePrefix(key), value);
                }
            });
        }
        metadata.put(Buckets.META_SHA256, sha256);
        return metadata;
    }

    /** Accept either {@code sha256} or {@code x-amz-meta-sha256} from callers without doubling it. */
    private static String stripWirePrefix(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.startsWith(Buckets.X_AMZ_META_PREFIX)
                ? lower.substring(Buckets.X_AMZ_META_PREFIX.length())
                : lower;
    }

    private static String resolveContentType(String contentType, String objectKey) {
        return contentType == null || contentType.isBlank()
                ? Buckets.contentTypeFor(objectKey)
                : contentType;
    }
}
