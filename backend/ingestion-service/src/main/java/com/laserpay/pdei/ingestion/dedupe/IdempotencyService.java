package com.laserpay.pdei.ingestion.dedupe;

import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.persistence.entity.ProcessedEventId;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Decides whether a submitted event has been seen before.
 *
 * <p><strong>Fast path.</strong> Redis {@code SETNX pdei:idem:{eventId}} with a 7 day TTL
 * (PLATFORM-CONTRACT section 12). {@code SETNX} is a single atomic round trip: the check and the
 * claim cannot interleave, so two concurrent submissions of the same fact cannot both win.
 *
 * <p><strong>Durable path.</strong> When Redis is unavailable - and it is a cache, so it will be -
 * the claim falls through to {@code ProcessedEventRepository.markProcessed(eventId, consumerGroup)},
 * an {@code INSERT ... ON CONFLICT DO NOTHING} against {@code pdei.processed_events}. Same
 * semantics, higher latency, survives a Redis flush. The consumer group recorded is
 * {@code pdei-ingestion-service}, which keeps ingestion's dedupe ledger distinct from every
 * downstream consumer's.
 *
 * <p><strong>Both down.</strong> {@code ingestion.dedupe.fail-open} (default true) accepts the
 * event. This is a considered trade, not laziness: every consumer in PDEI is idempotent by contract
 * (rule 9), so a duplicate on {@code pdei.raw.events.v1} converges to the same state, whereas a
 * refused {@code PaymentCaptured} is a fact the platform never learns. Set it to false where losing
 * an event is preferable to double-processing it.
 *
 * <p><strong>Key identity.</strong> The claim is made on the event's <em>idempotency key</em>,
 * which defaults to its event id - so the Redis key is literally {@code pdei:idem:{eventId}} in the
 * common case, and honours a caller-supplied {@code Idempotency-Key} header when one is given. Keys
 * longer than 64 characters are folded to their SHA-256 hex digest, because
 * {@code processed_events.event_id} is {@code VARCHAR(64)} and the two stores must agree on
 * identity or the fallback would not be a fallback.
 */
@Service
public class IdempotencyService {

    /** Maximum key length the durable store accepts; see {@code V1__baseline.sql}. */
    public static final int MAX_KEY_LENGTH = 64;

    private static final String MARKER = "1";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<ProcessedEventRepository> repositoryProvider;
    private final IngestionProperties properties;

    /** Latches so a Redis outage logs once at WARN, not once per event. */
    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);
    private final AtomicBoolean postgresDegraded = new AtomicBoolean(false);

    public IdempotencyService(ObjectProvider<StringRedisTemplate> redisProvider,
                              ObjectProvider<ProcessedEventRepository> repositoryProvider,
                              IngestionProperties properties) {
        this.redisProvider = redisProvider;
        this.repositoryProvider = repositoryProvider;
        this.properties = properties;
    }

    /** Outcome of a claim attempt. */
    public enum Decision {
        /** This caller owns the key; process the event. */
        FIRST_SEEN,
        /** The key was already claimed; suppress the event. */
        DUPLICATE
    }

    /**
     * Atomically claims {@code key} for the ingestion consumer group.
     *
     * @param key the event's idempotency key (defaults to its event id)
     * @return {@link Decision#FIRST_SEEN} when this call won the claim
     * @throws UpstreamUnavailableException when neither store is reachable and
     *         {@code ingestion.dedupe.fail-open} is false
     */
    public Decision claim(String key) {
        IngestionProperties.Dedupe config = properties.getDedupe();
        if (!config.isEnabled()) {
            return Decision.FIRST_SEEN;
        }
        String id = normalize(key);

        Decision fromRedis = claimInRedis(id, config);
        if (fromRedis != null) {
            return fromRedis;
        }
        return claimInPostgres(id, config);
    }

    /**
     * Releases a claim so a failed publication can be retried without the retry being mistaken for
     * a duplicate. Best effort by design: if the release itself fails the worst outcome is that the
     * caller must wait out the TTL, which is strictly better than losing the dedupe guarantee.
     */
    public void release(String key) {
        if (!properties.getDedupe().isEnabled()) {
            return;
        }
        String id = normalize(key);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null && properties.getDedupe().isRedisEnabled()) {
            try {
                redis.delete(redisKey(id));
            } catch (RuntimeException e) {
                log.warn("Could not release Redis idempotency claim {}: {}", id, e.toString());
            }
        }
        ProcessedEventRepository repository = repositoryProvider.getIfAvailable();
        if (repository != null && properties.getDedupe().isPostgresFallbackEnabled()) {
            try {
                repository.deleteById(new ProcessedEventId(id, ConsumerGroups.PDEI_INGESTION_SERVICE));
            } catch (RuntimeException e) {
                log.warn("Could not release Postgres idempotency claim {}: {}", id, e.toString());
            }
        }
    }

    /** The Redis key this service would use for a given idempotency key; exposed for diagnostics. */
    public String redisKey(String key) {
        return properties.getDedupe().getKeyPrefix() + normalize(key);
    }

    // --- backends -------------------------------------------------------------------------

    /** @return the decision, or null when Redis could not answer and the caller must fall through */
    private Decision claimInRedis(String id, IngestionProperties.Dedupe config) {
        if (!config.isRedisEnabled()) {
            return null;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        Duration ttl = config.getTtl() == null ? Duration.ofDays(7) : config.getTtl();
        try {
            Boolean firstSeen = redis.opsForValue()
                    .setIfAbsent(config.getKeyPrefix() + id, MARKER, ttl);
            if (firstSeen == null) {
                // Null means the command executed inside a pipeline/transaction with no immediate
                // result. Ingestion never pipelines, so this is anomalous: fall through rather than
                // guess, and let Postgres be authoritative.
                log.warn("Redis SETNX returned no result for {}; falling back to Postgres", id);
                return null;
            }
            if (redisDegraded.compareAndSet(true, false)) {
                log.info("Redis idempotency fast path recovered");
            }
            return firstSeen ? Decision.FIRST_SEEN : Decision.DUPLICATE;
        } catch (RuntimeException e) {
            if (redisDegraded.compareAndSet(false, true)) {
                log.warn("Redis idempotency fast path unavailable, falling back to processed_events: {}",
                        e.toString());
            }
            return null;
        }
    }

    private Decision claimInPostgres(String id, IngestionProperties.Dedupe config) {
        ProcessedEventRepository repository = config.isPostgresFallbackEnabled()
                ? repositoryProvider.getIfAvailable()
                : null;
        if (repository == null) {
            return whenNoStoreAvailable(id, config, null);
        }
        try {
            boolean firstTime = repository.markProcessed(id, ConsumerGroups.PDEI_INGESTION_SERVICE);
            if (postgresDegraded.compareAndSet(true, false)) {
                log.info("Postgres idempotency ledger recovered");
            }
            return firstTime ? Decision.FIRST_SEEN : Decision.DUPLICATE;
        } catch (RuntimeException e) {
            if (postgresDegraded.compareAndSet(false, true)) {
                log.error("Postgres idempotency ledger unavailable: {}", e.toString());
            }
            return whenNoStoreAvailable(id, config, e);
        }
    }

    private Decision whenNoStoreAvailable(String id, IngestionProperties.Dedupe config, RuntimeException cause) {
        if (config.isFailOpen()) {
            log.warn("No idempotency store available for {}; accepting the event unchecked "
                    + "(downstream consumers dedupe on eventId)", id);
            return Decision.FIRST_SEEN;
        }
        throw new UpstreamUnavailableException("idempotency-store",
                "neither Redis nor Postgres could claim " + id, cause);
    }

    // --- keys -----------------------------------------------------------------------------

    /**
     * Folds an arbitrary caller-supplied key into something both stores accept: non-blank, at most
     * {@value #MAX_KEY_LENGTH} characters. Long keys become their SHA-256 hex digest, which is
     * exactly 64 characters and collision-resistant, so identity is preserved.
     */
    static String normalize(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        return trimmed.length() <= MAX_KEY_LENGTH ? trimmed : Hashes.sha256Hex(trimmed);
    }
}
