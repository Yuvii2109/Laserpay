package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.core.util.RedisLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * The distributed half of the recomputation debounce: {@code pdei:lock:readiness:{transactionId}}
 * (PLATFORM-CONTRACT section 12).
 *
 * <p>Acquisition and release delegate to {@code evidence-core}'s {@link RedisLocks}, which owns the
 * {@code pdei:lock:} namespace and the SET NX PX / compare-and-delete primitive. What this wrapper
 * adds is a distinction {@link RedisLocks} deliberately does not make: it collapses "someone else
 * holds it" and "Redis is unreachable" into the same {@code null} token, and the correct reaction to
 * those two is opposite.
 *
 * <ul>
 *   <li><strong>Held elsewhere</strong> - skip. Another replica is scoring the same transaction from
 *       the same database, and readiness is deterministic, so its answer is our answer.</li>
 *   <li><strong>Redis unreachable</strong> - compute anyway. Redis is a coordination convenience,
 *       never the financial store (reference section 23). Degrading to duplicated work is
 *       acceptable; degrading to <em>no readiness at all</em> because a cache is down is not.</li>
 * </ul>
 *
 * <p>The two are told apart by probing the key after a failed acquire: if the probe answers, the
 * lock genuinely belongs to someone else; if the probe throws, Redis is the problem.
 */
public class RecomputeLock {

    private static final Logger log = LoggerFactory.getLogger(RecomputeLock.class);

    /** {@link RedisLocks} prefixes {@code pdei:lock:}, so this is the second half of the key. */
    static final String RESOURCE_PREFIX = "readiness:";

    /** Full Redis key, for logs, tests and documentation. */
    public static String key(String transactionId) {
        return "pdei:lock:" + RESOURCE_PREFIX + transactionId;
    }

    static String resource(String transactionId) {
        return RESOURCE_PREFIX + transactionId;
    }

    private final RedisLocks locks;
    private final StringRedisTemplate redis;

    public RecomputeLock(RedisLocks locks, StringRedisTemplate redis) {
        this.locks = locks;
        this.redis = redis;
    }

    /**
     * Try to take the lock for one transaction.
     *
     * @return {@link Acquisition#acquired} with a release token, {@link Acquisition#heldElsewhere()}
     *     when another worker owns it, or {@link Acquisition#unavailable()} when there is no working
     *     Redis to coordinate through
     */
    public Acquisition acquire(String transactionId, Duration ttl, int attempts, Duration backoff) {
        if (locks == null || redis == null) {
            return Acquisition.unavailable();
        }
        String token = locks.lock(resource(transactionId), ttl, Math.max(1, attempts), backoff);
        if (token != null) {
            return Acquisition.acquired(token);
        }
        return probe(transactionId) ? Acquisition.heldElsewhere() : Acquisition.unavailable();
    }

    /** Release a lock this worker owns. Safe to call with a null token. */
    public void release(String transactionId, String token) {
        if (locks == null || token == null) {
            return;
        }
        locks.unlock(resource(transactionId), token);
    }

    /**
     * @return true when Redis answered and the key exists (a real holder), false when Redis did not
     *     answer or the key vanished between the failed acquire and the probe
     */
    private boolean probe(String transactionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(transactionId)));
        } catch (RuntimeException e) {
            log.warn("redis unreachable while probing the readiness lock for transactionId={};"
                    + " computing without coordination: {}", transactionId, e.toString());
            return false;
        }
    }

    /** Outcome of an acquisition attempt. */
    public record Acquisition(boolean owned, boolean coordinated, String token) {

        static Acquisition acquired(String token) {
            return new Acquisition(true, true, token);
        }

        /** Another worker holds the lock: this recomputation is a duplicate and must be dropped. */
        static Acquisition heldElsewhere() {
            return new Acquisition(false, true, null);
        }

        /** No coordination available: proceed uncoordinated rather than skip the computation. */
        static Acquisition unavailable() {
            return new Acquisition(false, false, null);
        }

        /** True when the caller should perform the computation. */
        public boolean shouldProceed() {
            return owned || !coordinated;
        }

        /** True when the caller must drop this recomputation as a coalesced duplicate. */
        public boolean isDuplicate() {
            return !owned && coordinated;
        }
    }
}
