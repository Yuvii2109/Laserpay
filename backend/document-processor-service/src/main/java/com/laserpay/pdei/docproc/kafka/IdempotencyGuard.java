package com.laserpay.pdei.docproc.kafka;

import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.docproc.config.DocProcProperties;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The idempotency claim every PDEI consumer makes before doing any work
 * (platform contract 4: "dedupe on {@code eventId} via Redis SETNX + Postgres
 * {@code processed_events}").
 *
 * <p>Two layers, and the order matters:
 * <ul>
 *   <li><strong>Redis {@code SETNX pdei:idem:{eventId}}</strong> is an advisory fast path. A hit
 *       means "probably seen", not "definitely seen", because the key can outlive a transaction
 *       that rolled back. So a Redis hit is confirmed against Postgres before anything is
 *       skipped - the cost of a wrong skip is a document that is never indexed, and no cache is
 *       worth that.</li>
 *   <li><strong>Postgres {@code INSERT ... ON CONFLICT DO NOTHING}</strong> is authoritative and
 *       atomic: exactly one caller gets {@code true}, even across instances, and the marker
 *       commits in the same transaction as the work it guards.</li>
 * </ul>
 *
 * <p>Redis being down degrades this to the Postgres claim alone, which is still correct - just
 * one round-trip busier.
 */
@Component
public class IdempotencyGuard {

    /** Platform contract 12: {@code pdei:idem:{eventId}}, TTL 7d. */
    private static final String KEY_PREFIX = "pdei:idem:";
    private static final String CONSUMER_GROUP = ConsumerGroups.PDEI_DOCUMENT_PROCESSOR_SERVICE;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final ProcessedEventRepository processedEvents;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final DocProcProperties properties;

    public IdempotencyGuard(ProcessedEventRepository processedEvents,
                            ObjectProvider<StringRedisTemplate> redisTemplates,
                            DocProcProperties properties) {
        this.processedEvents = processedEvents;
        this.redisTemplates = redisTemplates;
        this.properties = properties;
    }

    /**
     * Claim an event for this consumer group.
     *
     * @return {@code true} when this call is the first sighting and the caller must process the
     *     event; {@code false} when it is a duplicate, replay or redelivery
     */
    public boolean claim(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        if (redisSaysSeen(eventId) && processedEvents.wasProcessed(eventId, CONSUMER_GROUP)) {
            return false;
        }
        return processedEvents.markProcessed(eventId, CONSUMER_GROUP);
    }

    /**
     * @return {@code true} when Redis already held the key (so this event has probably been seen)
     */
    private boolean redisSaysSeen(String eventId) {
        if (!properties.isRedisDedupeEnabled()) {
            return false;
        }
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            Boolean firstTime = redis.opsForValue()
                    .setIfAbsent(KEY_PREFIX + eventId, CONSUMER_GROUP, properties.getIdempotencyTtl());
            return Boolean.FALSE.equals(firstTime);
        } catch (RuntimeException e) {
            // Redis is a cache here, never the source of truth: a failure means one extra
            // Postgres round-trip, not a dropped or double-processed event.
            log.debug("Redis dedupe unavailable, falling back to Postgres only: {}", e.toString());
            return false;
        }
    }
}
