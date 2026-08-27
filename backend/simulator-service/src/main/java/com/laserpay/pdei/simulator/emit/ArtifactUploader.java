package com.laserpay.pdei.simulator.emit;

import com.laserpay.pdei.core.storage.Buckets;
import com.laserpay.pdei.core.storage.ObjectStore;
import com.laserpay.pdei.simulator.config.SimulatorProperties;
import com.laserpay.pdei.simulator.world.SyntheticArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the generated evidence bytes into MinIO so the artifacts a simulated world talks about
 * actually exist.
 *
 * <p>This is what closes the loop between the two halves of a demo. Without it,
 * {@code document-processor-service} has nothing to extract, evidence search returns nothing,
 * {@code EvidenceIntegrityService} has no bytes to re-hash, and the {@code DELETE_EVIDENCE} and
 * {@code CORRUPT_EVIDENCE_HASH} chaos types have nothing to break. With it, every one of those
 * paths runs against real objects at the contract key layout
 * ({@code {merchantId}/{transactionId}/{evidenceType}/{evidenceId}/v{version}/{filename}}).
 *
 * <p>Degrades quietly: no {@link ObjectStore} bean (no MinIO in this environment) means the run
 * still emits its events, it just has no bytes behind them. A simulator that refused to start
 * because object storage was down would be worse than useless.
 */
@Service
public class ArtifactUploader {

    private static final Logger log = LoggerFactory.getLogger(ArtifactUploader.class);

    private final ObjectProvider<ObjectStore> objectStores;
    private final SimulatorProperties properties;

    public ArtifactUploader(ObjectProvider<ObjectStore> objectStores, SimulatorProperties properties) {
        this.objectStores = objectStores;
        this.properties = properties;
    }

    /**
     * Uploads up to {@code pdei.simulator.artifacts.max-uploads} artifacts.
     *
     * @return how many objects were written
     */
    public long upload(List<SyntheticArtifact> artifacts) {
        if (!properties.getArtifacts().isUpload() || artifacts.isEmpty()) {
            return 0L;
        }
        ObjectStore store = objectStores.getIfAvailable();
        if (store == null) {
            log.info("no ObjectStore bean available; skipping upload of {} synthetic artifacts",
                    artifacts.size());
            return 0L;
        }

        int limit = Math.max(0, properties.getArtifacts().getMaxUploads());
        long written = 0;
        long failures = 0;
        for (SyntheticArtifact artifact : artifacts) {
            if (written >= limit) {
                log.info("artifact upload cap of {} reached; {} artifacts left on the floor",
                        limit, artifacts.size() - written);
                break;
            }
            try {
                store.put(Buckets.EVIDENCE, artifact.objectKey(), artifact.content(),
                        artifact.contentType(), userMetadata(artifact));
                written++;
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 5) {
                    log.warn("could not upload artifact {}: {}", artifact.objectKey(), e.toString());
                }
            }
        }
        if (failures > 0) {
            log.warn("{} of {} synthetic artifacts failed to upload", failures, artifacts.size());
        }
        return written;
    }

    /**
     * The user metadata every evidence object carries (platform contract 11). The sha256 here is
     * the one recorded in the EvidenceAdded event, so an integrity check compares like with like
     * and {@code CORRUPT_EVIDENCE_HASH} has something meaningful to break.
     */
    private static Map<String, String> userMetadata(SyntheticArtifact artifact) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(Buckets.META_SHA256, artifact.sha256());
        metadata.put(Buckets.META_EVIDENCE_ID, artifact.evidenceId());
        metadata.put(Buckets.META_VERSION, "1");
        return metadata;
    }
}
