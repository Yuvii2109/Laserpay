package com.laserpay.pdei.audit.consume;

import com.laserpay.pdei.audit.metrics.AuditMetrics;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis {@code SETNX} on {@code pdei:idem:{eventId}} in front of the Postgres
 * {@code processed_events} claim (PLATFORM-CONTRACT section 4).
 *
 * <p>Idempotency matters more here than anywhere else in the platform. A duplicate readiness
 * computation costs a few milliseconds and produces the same number; a duplicate audit append would
 * add a second, indistinguishable link to a tamper-evident chain for a fact that happened once.
 *
 * <p>So this service defends twice over, and this guard is only the outer layer:
 *
 * <ol>
 *   <li>this claim, which stops the work from starting;</li>
 *   <li>{@code AuditChainAppender}'s {@code store.exists(auditId)} check plus
 *       {@code ON CONFLICT (audit_id) DO NOTHING}, which stops a duplicate even if the claim is
 *       lost - and it can be lost, because the audit id derived from a canonical event is stable
 *       across a full topic replay while {@code processed_events} may have been pruned.</li>
 * </ol>
 *
 * <p>A Redis "already seen" is never final. If a previous attempt set the key and then crashed
 * before committing, the event was not handled, so Postgres is asked to settle it.
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);
    private static final String KEY_PREFIX = "pdei:idem:";

    private final ProcessedEventRepository processedEvents;
    private final StringRedisTemplate redis;
    private final String consumerGroup;
    private final Duration ttl;
    private final AuditMetrics metrics;

    public IdempotencyGuard(ProcessedEventRepository processedEvents, StringRedisTemplate redis,
                            String consumerGroup, Duration ttl, AuditMetrics metrics) {
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
     * @return true when this call is the first sighting and the caller must process the event
     */
    public boolean claim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // Nothing to dedupe on. The append path still refuses to store the same audit id twice.
            return true;
        }
        boolean redisSaysNew = markInRedis(eventId);
        if (!redisSaysNew && processedEvents.wasProcessed(eventId, consumerGroup)) {
            duplicate();
            return false;
        }
        if (!processedEvents.markProcessed(eventId, consumerGroup)) {
            duplicate();
            return false;
        }
        return true;
    }

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
