package com.laserpay.pdei.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Short-lived distributed lock on the {@code pdei:lock:{resource}} namespace (platform contract 12).
 *
 * <p>Used by the audit recorder to keep the per-merchant hash chain linear. Deliberately simple:
 * SET NX PX for acquire, compare-and-delete for release. If Redis is unavailable the lock degrades
 * to "not acquired" and callers fall back to their own serialisation (a database unique index on
 * {@code (merchant_id, previous_hash)} is the real backstop).</p>
 */
public final class RedisLocks {

    private static final Logger log = LoggerFactory.getLogger(RedisLocks.class);
    private static final String PREFIX = "pdei:lock:";

    private final StringRedisTemplate redis;

    public RedisLocks(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Acquire, returning the release token, or {@code null} when the lock is held elsewhere. */
    public String tryLock(String resource, Duration ttl) {
        if (redis == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(PREFIX + resource, token, ttl);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (RuntimeException e) {
            log.warn("redis lock unavailable for resource={}: {}", resource, e.toString());
            return null;
        }
    }

    /**
     * Acquire with bounded retries. Returns the token or {@code null}. Never throws - the caller
     * decides whether losing the lock is fatal.
     */
    public String lock(String resource, Duration ttl, int attempts, Duration backoff) {
        for (int attempt = 0; attempt < Math.max(1, attempts); attempt++) {
            String token = tryLock(resource, ttl);
            if (token != null) {
                return token;
            }
            try {
                Thread.sleep(Math.max(1L, backoff.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Release only when we still own the lock. */
    public void unlock(String resource, String token) {
        if (redis == null || token == null) {
            return;
        }
        try {
            String current = redis.opsForValue().get(PREFIX + resource);
            if (token.equals(current)) {
                redis.delete(PREFIX + resource);
            }
        } catch (RuntimeException e) {
            log.warn("redis unlock failed for resource={}: {}", resource, e.toString());
        }
    }
}
