package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency for the control-tower consumer: has this gateway already fanned out this event?
 *
 * <h2>Key</h2>
 * <p>{@code pdei:idem:{eventId}:pdei-api-gateway-service}, inside the contract section 12
 * {@code pdei:idem:} namespace but suffixed with the consumer group. The suffix is not decoration.
 * The bare key {@code pdei:idem:{eventId}} is shared by every consumer of the platform, so the first
 * service to claim an event would make every other service treat it as a duplicate and skip it.
 * The Postgres side of the same primitive already models this correctly:
 * {@code processed_events} is keyed on {@code (event_id, consumer_group)}. This mirrors that.</p>
 *
 * <h2>Why Redis only</h2>
 * <p>The gateway does not write to {@code processed_events}. That table is the durable ledger for
 * consumers that mutate financial state and must never process an event twice; this consumer only
 * pushes a JSON frame at a browser. Paying a database write per event to protect a UI refresh would
 * be the wrong trade, and a duplicated frame is invisible to the user anyway.</p>
 *
 * <h2>Failure</h2>
 * <p>With Redis unavailable the check falls back to a bounded in-memory set, which is enough to
 * absorb the duplicate-within-seconds case a consumer rebalance produces, and admits the rest. This
 * fails <em>open</em>, deliberately: for a read-only fan-out, showing a frame twice is a much smaller
 * problem than showing none, which is what failing closed would do for as long as Redis is down.</p>
 */
@Component
public class StreamEventDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(StreamEventDeduplicator.class);

    private static final String KEY_PREFIX = "pdei:idem:";
    private static final String GROUP_SUFFIX = ":" + ConsumerGroups.PDEI_API_GATEWAY_SERVICE;
    private static final String MARKER = "1";

    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final Duration ttl;
    private final Set<String> localSeen;

    public StreamEventDeduplicator(ObjectProvider<StringRedisTemplate> redisTemplates,
                                   ApiProperties properties) {
        this.redisTemplates = redisTemplates;
        this.ttl = properties.getStream().getDedupeTtl();
        int capacity = Math.max(256, properties.getStream().getLocalDedupeCapacity());
        this.localSeen = Collections.synchronizedSet(
                Collections.newSetFromMap(new BoundedLinkedHashMap<>(capacity)));
    }

    /**
     * @return true when this event has not been fanned out before and should be delivered now
     */
    public boolean firstTime(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // An event with no id cannot be deduplicated. Delivering it is the safer of the two
            // wrong answers: a missing frame is a stale dashboard, a repeated frame is a redraw.
            return true;
        }
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return localSeen.add(eventId);
        }
        try {
            Boolean claimed = redis.opsForValue()
                    .setIfAbsent(KEY_PREFIX + eventId + GROUP_SUFFIX, MARKER, ttl);
            return claimed == null || claimed;
        } catch (RuntimeException e) {
            log.debug("Redis dedupe unavailable, falling back to the local set: {}", e.toString());
            return localSeen.add(eventId);
        }
    }

    /** Visible for diagnostics: how many ids the in-memory fallback is currently holding. */
    public int localSeenSize() {
        return localSeen.size();
    }

    /** Fixed-capacity LRU map; the eldest id is evicted once the cap is reached. */
    private static final class BoundedLinkedHashMap<K, V> extends LinkedHashMap<K, V> {

        private final int capacity;

        private BoundedLinkedHashMap(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
