package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.ProcessedEventEntity;
import com.laserpay.pdei.persistence.entity.ProcessedEventId;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The canonical Postgres-side idempotency primitive for every Kafka consumer in PDEI.
 *
 * <p>Usage in a consumer:
 *
 * <pre>{@code
 * if (!processedEvents.markProcessed(event.eventId(), ConsumerGroups.PDEI_READINESS_WORKER)) {
 *     meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL, "service", "readiness-worker").increment();
 *     return; // already handled: duplicate, replay or redelivery after a crash
 * }
 * handle(event);
 * }</pre>
 *
 * <p>Why {@code INSERT ... ON CONFLICT DO NOTHING} rather than SELECT-then-INSERT: the check and
 * the claim are one atomic statement, so two consumer threads racing on the same event cannot
 * both win. Exactly one call returns {@code true}.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, ProcessedEventId> {

    /**
     * Atomically claims {@code (eventId, consumerGroup)}.
     *
     * @return {@code true} if this call inserted the row (first sighting - the caller must
     *     process the event), {@code false} if the pair was already recorded (duplicate).
     */
    default boolean markProcessed(String eventId, String consumerGroup) {
        return insertIfAbsent(eventId, consumerGroup) == 1;
    }

    /**
     * Native upsert-free claim. Returns the number of rows actually inserted: 1 on the first
     * sighting, 0 when the composite primary key already exists.
     *
     * <p>{@code REQUIRED} propagation on purpose: the marker must commit in the same transaction
     * as the consumer's side effects, otherwise a crash between the two would either lose the
     * work or silently skip it on redelivery.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRED)
    @Query(value = """
            INSERT INTO pdei.processed_events (event_id, consumer_group, processed_at)
            VALUES (:eventId, :consumerGroup, now())
            ON CONFLICT (event_id, consumer_group) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId, @Param("consumerGroup") String consumerGroup);

    /** Read-only dedupe check; prefer {@link #markProcessed} which claims atomically. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM pdei.processed_events
                WHERE event_id = :eventId AND consumer_group = :consumerGroup)
            """, nativeQuery = true)
    boolean wasProcessed(@Param("eventId") String eventId, @Param("consumerGroup") String consumerGroup);

    /** Every consumer group that has already handled this event (replay diagnostics). */
    @Query(value = """
            SELECT consumer_group FROM pdei.processed_events WHERE event_id = :eventId
            """, nativeQuery = true)
    List<String> findConsumerGroupsFor(@Param("eventId") String eventId);

    long countByIdConsumerGroup(String consumerGroup);

    List<ProcessedEventEntity> findByIdConsumerGroupAndProcessedAtAfter(String consumerGroup, Instant after);

    /**
     * Retention pruning. The dedupe window matches the Redis TTL of {@code pdei:idem:{eventId}}
     * (7 days); anything older cannot still be in flight.
     *
     * @return number of rows removed
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pdei.processed_events WHERE processed_at < :before", nativeQuery = true)
    int deleteProcessedBefore(@Param("before") Instant before);
}
