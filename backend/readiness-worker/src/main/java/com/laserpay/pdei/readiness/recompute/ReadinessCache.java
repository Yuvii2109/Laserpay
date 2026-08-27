package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.model.ReadinessSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * The hot readiness cache: {@code pdei:readiness:{transactionId}}, TTL 10 minutes
 * (PLATFORM-CONTRACT section 12).
 *
 * <p>The cache holds the snapshot JSON that {@code GET /transactions/{id}/readiness} serves, so the
 * control tower can render a merchant's whole list without touching Postgres for every row. It is a
 * <em>cache</em> in the strict sense: {@code readiness_snapshots} is authoritative, and losing every
 * key here costs latency and nothing else. Redis is never the financial store (reference section 23).
 *
 * <p>Every operation swallows Redis failures. A cache write that throws must not roll back a
 * correctly computed and persisted score.
 */
public class ReadinessCache {

    private static final Logger log = LoggerFactory.getLogger(ReadinessCache.class);
    private static final String KEY_PREFIX = "pdei:readiness:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ReadinessCache(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(10) : ttl;
    }

    public static String key(String transactionId) {
        return KEY_PREFIX + transactionId;
    }

    public Duration ttl() {
        return ttl;
    }

    /** Cache a freshly computed snapshot. Returns false when Redis is unavailable. */
    public boolean put(ReadinessSnapshot snapshot) {
        if (redis == null || snapshot == null || snapshot.transactionId() == null) {
            return false;
        }
        try {
            redis.opsForValue().set(key(snapshot.transactionId()), Json.write(snapshot), ttl);
            return true;
        } catch (RuntimeException e) {
            log.warn("readiness cache write failed for transactionId={}: {}",
                    snapshot.transactionId(), e.toString());
            return false;
        }
    }

    public Optional<ReadinessSnapshot> get(String transactionId) {
        if (redis == null || transactionId == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(transactionId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(Json.read(json, ReadinessSnapshot.class));
        } catch (RuntimeException e) {
            log.warn("readiness cache read failed for transactionId={}: {}", transactionId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Drop a cached snapshot.
     *
     * <p>Used when an event is known to have changed the inputs but the recomputation has not run
     * yet: a stale-but-present score is worse than a cache miss, because a reader cannot tell the
     * difference between the two.
     */
    public void evict(String transactionId) {
        if (redis == null || transactionId == null) {
            return;
        }
        try {
            redis.delete(key(transactionId));
        } catch (RuntimeException e) {
            log.warn("readiness cache evict failed for transactionId={}: {}", transactionId, e.toString());
        }
    }
}
