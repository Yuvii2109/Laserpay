package com.laserpay.pdei.readiness.consume;

import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * The two-layer idempotency check every PDEI consumer performs (PLATFORM-CONTRACT section 4):
 * Redis {@code SETNX} on {@code pdei:idem:{eventId}} in front of the Postgres
 * {@code processed_events} claim.
 *
 * <p>Why both. Redis answers "almost certainly a duplicate" in microseconds and absorbs the
 * redelivery storms that follow a rebalance, but it can lose keys and it is not transactional.
 * Postgres {@code INSERT ... ON CONFLICT DO NOTHING} is atomic and durable and commits in the same
 * transaction as the work, so exactly one caller can ever win - it is the authority. Redis is the
 * cheap filter in front of it, never the answer on its own.
 *
 * <p>Consequently a Redis "already seen" is <strong>not</strong> treated as final: if the previous
 * attempt set the key and then crashed before committing, the event was never actually handled, and
 * Postgres is asked to settle it. The cost of being wrong in that direction is one wasted query;
 * the cost of the other direction is a silently dropped event.
 *
 * <p>Dedupe is per consumer group, so a replay for one service does not mark the event handled for
 * every other service.
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);
    private static final String KEY_PREFIX = "pdei:idem:";

    private final ProcessedEventRepository processedEvents;
    private final StringRedisTemplate redis;
    private final String consumerGroup;
    private final Duration ttl;
    private final ReadinessWorkerMetrics metrics;

    public IdempotencyGuard(ProcessedEventRepository processedEvents, StringRedisTemplate redis,
                            String consumerGroup, Duration ttl, ReadinessWorkerMetrics metrics) {
        this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents must not be null");
        this.redis = redis;
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(7) : ttl;
        this.metrics = metrics;
    }

    public static String key(String eventId) {
        return KEY_PREFIX + eventId;
    }

    /**
     * Claim an event for this consumer group.
     *
     * @return {@code true} when this call is the first sighting and the caller must process the
     *     event; {@code false} when it is a duplicate, a replay or a redelivery
     */
    public boolean claim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // An event without an id cannot be deduplicated. Process it and let the deterministic
            // recomputation absorb the repetition - readiness is idempotent by construction.
            return true;
        }

        boolean redisSaysNew = markInRedis(eventId);
        if (!redisSaysNew && processedEvents.wasProcessed(eventId, consumerGroup)) {
            duplicate();
            return false;
        }

        boolean firstTime = processedEvents.markProcessed(eventId, consumerGroup);
        if (!firstTime) {
            duplicate();
            return false;
        }
        return true;
    }

    /**
     * Best-effort Redis claim.
     *
     * @return true when this call set the key (probably new), false when the key existed or Redis
     *     is unavailable - both of which merely send the decision to Postgres
     */
    private boolean markInRedis(String eventId) {
        if (redis == null) {
            return false;
        }
        try {
            Boolean set = redis.opsForValue().setIfAbsent(key(eventId), consumerGroup, ttl);
            return Boolean.TRUE.equals(set);
        } catch (RuntimeException e) {
            log.debug("redis idempotency unavailable, falling through to postgres: {}", e.toString());
            return false;
        }
    }

    private void duplicate() {
        if (metrics != null) {
            metrics.duplicateSuppressed();
        }
    }
}
