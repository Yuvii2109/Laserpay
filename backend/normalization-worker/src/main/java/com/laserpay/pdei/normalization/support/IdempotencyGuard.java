package com.laserpay.pdei.normalization.support;

import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Two-tier consumer idempotency: Redis as a cache of completions, Postgres as the authority.
 *
 * <p>PLATFORM-CONTRACT section 4 requires every consumer to be idempotent, deduping on
 * {@code eventId} through Redis and {@code processed_events}. The ordering of those two matters
 * more than it looks:
 *
 * <ol>
 *   <li><strong>Redis is consulted first, but only as a negative cache of <em>completed</em>
 *       work.</strong> The key is written after processing succeeds, never before. A Redis hit
 *       therefore proves the event was fully handled; a miss proves nothing and falls through.</li>
 *   <li><strong>Postgres performs the actual claim</strong> via
 *       {@code INSERT ... ON CONFLICT DO NOTHING}, which is atomic - two consumer threads racing on
 *       the same event cannot both win.</li>
 * </ol>
 *
 * <p>The alternative - claiming in Redis with SETNX before processing - is faster and wrong: a crash
 * between the claim and the work would leave a claimed-but-unprocessed event that redelivery can
 * never rescue. Losing a financial event to save a database round trip is not a trade this platform
 * makes.
 *
 * <p>Redis is optional. Every call degrades to Postgres-only when Redis is absent or unreachable, so
 * a Redis outage costs latency, not correctness.
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** Key namespace from PLATFORM-CONTRACT section 12. */
    private static final String KEY_PREFIX = "pdei:idem:";

    private final ProcessedEventRepository processedEvents;
    private final StringRedisTemplate redis;
    private final String consumerGroup;
    private final Duration ttl;

    private volatile boolean redisAvailable;

    public IdempotencyGuard(ProcessedEventRepository processedEvents,
                            StringRedisTemplate redis,
                            String consumerGroup,
                            Duration ttl) {
        this.processedEvents = processedEvents;
        this.redis = redis;
        this.consumerGroup = consumerGroup;
        this.ttl = ttl == null ? Duration.ofDays(7) : ttl;
        this.redisAvailable = redis != null;
    }

    /**
     * Attempts to claim an event for processing.
     *
     * <p>Must be called inside the same transaction as the work it guards: the claim and the side
     * effects have to commit together, or a crash between them either loses the work or skips it
     * forever on redelivery.
     *
     * @return {@code true} when this caller owns the event and must process it; {@code false} when
     *         it is a duplicate, a replay or a redelivery that was already handled
     */
    public boolean claim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        if (seenInCache(eventId)) {
            return false;
        }
        boolean firstTime = processedEvents.markProcessed(eventId, consumerGroup);
        if (!firstTime) {
            cache(eventId);
        }
        return firstTime;
    }

    /**
     * Records that an event finished processing, so subsequent redeliveries short-circuit in Redis
     * instead of hitting Postgres. Call only after the work has committed.
     */
    public void confirm(String eventId) {
        cache(eventId);
    }

    /*
     * The consumer group is part of the KEY, not just the value.
     *
     * pdei.canonical.events.v1 is consumed by state-builder-worker, audit-service and
     * document-processor-service, and pdei.evidence.events.v1 by readiness-worker and
     * audit-service. A bare pdei:idem:{eventId} is one namespace shared by all of them, so the
     * first service to SETNX an event claims it and every other consumer of that same event
     * concludes it is a duplicate and skips its own work - silently, with no error and no lag.
     *
     * Measured on a seeded run before this fix: of 3373 canonical events, audit-service claimed
     * 3778 rows in processed_events and state-builder-worker claimed 58. Only 48 of 324
     * transactions and 3 of 58 disputes were ever projected. Nothing failed; the work was simply
     * never done.
     *
     * Postgres already had this right: processed_events is keyed (event_id, consumer_group).
     * This makes the Redis fast path agree with the durable claim it is meant to shortcut.
     */
    private String cacheKey(String eventId) {
        return KEY_PREFIX + eventId + ":" + consumerGroup;
    }

    private boolean seenInCache(String eventId) {
        if (!redisAvailable) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(cacheKey(eventId)));
        } catch (RuntimeException e) {
            degrade(e);
            return false;
        }
    }

    private void cache(String eventId) {
        if (!redisAvailable || eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(cacheKey(eventId), consumerGroup, ttl);
        } catch (RuntimeException e) {
            degrade(e);
        }
    }

    /**
     * Marks Redis unusable after the first failure. Deliberately one-way for the process lifetime:
     * retrying a dead Redis on every event would add its connect timeout to every event's latency.
     */
    private void degrade(RuntimeException failure) {
        if (redisAvailable) {
            redisAvailable = false;
            log.warn("Redis idempotency cache disabled for this process after: {}. "
                    + "Postgres processed_events remains authoritative.", failure.toString());
        }
    }

    /** Visible for diagnostics and tests. */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }
}
