package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.persistence.entity.AuditEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Hash-chained audit log.
 *
 * <p>{@link #findChainHead} returns the link a new event must point at; {@link #streamChain}
 * walks a merchant chain in insertion order for {@code GET /audit/chain/verify} and for the
 * NDJSON export, without materialising the whole chain in memory.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, String> {

    Page<AuditEventEntity> findByEntityTypeAndEntityId(AggregateType entityType, String entityId, Pageable pageable);

    Page<AuditEventEntity> findByMerchantId(String merchantId, Pageable pageable);

    List<AuditEventEntity> findByCorrelationId(String correlationId);

    List<AuditEventEntity> findByActorAndOccurredAtBetween(String actor, Instant from, Instant to);

    Optional<AuditEventEntity> findByHash(String hash);

    /** Current tail of a merchant chain: its hash becomes the next event's previousHash. */
    Optional<AuditEventEntity> findTopByMerchantIdOrderBySequenceNoDesc(String merchantId);

    default Optional<AuditEventEntity> findChainHead(String merchantId) {
        return findTopByMerchantIdOrderBySequenceNoDesc(merchantId);
    }

    List<AuditEventEntity> findByMerchantIdOrderBySequenceNoAsc(String merchantId);

    /** Streaming chain walk. Call inside a read-only transaction and close the stream. */
    @Query("SELECT a FROM AuditEventEntity a WHERE a.merchantId = :merchantId ORDER BY a.sequenceNo ASC")
    Stream<AuditEventEntity> streamChain(@Param("merchantId") String merchantId);

    @Query("""
            SELECT a FROM AuditEventEntity a
            WHERE (:entityType IS NULL OR a.entityType = :entityType)
              AND (:entityId   IS NULL OR a.entityId = :entityId)
              AND (:actor      IS NULL OR a.actor = :actor)
              AND (:from       IS NULL OR a.occurredAt >= :from)
              AND (:to         IS NULL OR a.occurredAt <  :to)
            ORDER BY a.occurredAt DESC
            """)
    Page<AuditEventEntity> searchByFilters(@Param("entityType") AggregateType entityType,
                                           @Param("entityId") String entityId,
                                           @Param("actor") String actor,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Pageable pageable);

    long countByMerchantId(String merchantId);
}
