package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.persistence.entity.EvidenceEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Evidence access, including the Postgres full-text search behind
 * {@code GET /evidence?merchantId&type&status&q} and the evidence explorer.
 */
@Repository
public interface EvidenceRepository extends JpaRepository<EvidenceEntity, String> {

    // ---------------------------------------------------------------------------------
    // Derived queries declared by docs/SHARED-LIBRARY-API.md section 2
    // ---------------------------------------------------------------------------------

    List<EvidenceEntity> findByTransactionIdAndStatusIn(String transactionId, Collection<EvidenceStatus> statuses);

    List<EvidenceEntity> findByMerchantIdAndTypeAndStatus(String merchantId, EvidenceType type, EvidenceStatus status);

    /**
     * Content-hash lookup within a transaction — the deduplication check performed before
     * creating evidence from an event that may be a duplicate.
     *
     * <p>The method name is fixed by the shared-library contract, so the query is declared
     * explicitly rather than derived from the property name ({@code sha256}). Backed by the
     * partial unique index {@code ux_evidence_tx_sha}, which is what makes the result at most one.
     */
    @Query("""
            SELECT e FROM EvidenceEntity e
            WHERE e.sha256 = :sha256 AND e.transactionId = :transactionId
            """)
    Optional<EvidenceEntity> findByShaAndTransactionId(@Param("sha256") String sha256,
                                                       @Param("transactionId") String transactionId);

    /**
     * Full-text search over title / summary / type / filename / extracted text, ranked.
     *
     * <p>{@code websearch_to_tsquery} is used deliberately: it accepts raw human input
     * ({@code delivery proof "March 2026" -refund}) and never throws on malformed syntax, unlike
     * {@code to_tsquery}. Both parameters are optional — passing {@code null} for {@code tsQuery}
     * degrades to "latest evidence for this merchant".
     *
     * <p>Pass an UNSORTED {@link Pageable} ({@code PageRequest.of(page, size)}): Spring Data
     * cannot apply dynamic sorting to a native query, and the ranking order below is the point.
     */
    @Query(value = """
            SELECT e.* FROM pdei.evidence e
            WHERE (CAST(:merchantId AS text) IS NULL OR e.merchant_id = CAST(:merchantId AS text))
              AND (CAST(:tsQuery AS text) IS NULL
                   OR e.search_vector @@ websearch_to_tsquery('english', CAST(:tsQuery AS text)))
            ORDER BY
              CASE WHEN CAST(:tsQuery AS text) IS NULL THEN 0
                   ELSE ts_rank(e.search_vector, websearch_to_tsquery('english', CAST(:tsQuery AS text)))
              END DESC,
              e.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM pdei.evidence e
            WHERE (CAST(:merchantId AS text) IS NULL OR e.merchant_id = CAST(:merchantId AS text))
              AND (CAST(:tsQuery AS text) IS NULL
                   OR e.search_vector @@ websearch_to_tsquery('english', CAST(:tsQuery AS text)))
            """,
            nativeQuery = true)
    List<EvidenceEntity> search(@Param("tsQuery") String tsQuery,
                                @Param("merchantId") String merchantId,
                                Pageable pageable);

    /** Search narrowed to one transaction — used by the transaction detail view. */
    @Query(value = """
            SELECT e.* FROM pdei.evidence e
            WHERE e.transaction_id = :transactionId
              AND e.search_vector @@ websearch_to_tsquery('english', CAST(:tsQuery AS text))
            ORDER BY ts_rank(e.search_vector, websearch_to_tsquery('english', CAST(:tsQuery AS text))) DESC
            """, nativeQuery = true)
    List<EvidenceEntity> searchWithinTransaction(@Param("tsQuery") String tsQuery,
                                                 @Param("transactionId") String transactionId,
                                                 Pageable pageable);

    // ---------------------------------------------------------------------------------
    // Readiness / lifecycle support
    // ---------------------------------------------------------------------------------

    List<EvidenceEntity> findByTransactionId(String transactionId);

    List<EvidenceEntity> findByTransactionIdIn(Collection<String> transactionIds);

    List<EvidenceEntity> findByMerchantIdAndStatusIn(String merchantId, Collection<EvidenceStatus> statuses, Pageable pageable);

    List<EvidenceEntity> findByMerchantIdAndType(String merchantId, EvidenceType type, Pageable pageable);

    Optional<EvidenceEntity> findBySourceEventId(String sourceEventId);

    List<EvidenceEntity> findByRelatedEntityIdAndStatusIn(String relatedEntityId, Collection<EvidenceStatus> statuses);

    /** Nightly expiry sweep: items whose {@code expiresAt} has passed but are still live. */
    List<EvidenceEntity> findByExpiresAtBeforeAndStatusIn(Instant cutoff, Collection<EvidenceStatus> statuses);

    /** Expiry warning sweep: feeds EXPIRING_SOON gaps (-5 penalty on mandatory evidence). */
    List<EvidenceEntity> findByExpiresAtBetweenAndStatusIn(Instant from, Instant to, Collection<EvidenceStatus> statuses);

    long countByMerchantIdAndStatus(String merchantId, EvidenceStatus status);

    long countByTransactionIdAndStatusIn(String transactionId, Collection<EvidenceStatus> statuses);

    /** Distribution behind {@code pdei_evidence_total{type,status}} and the merchant summary. */
    @Query(value = """
            SELECT e.type AS type, e.status AS status, count(*) AS total
            FROM pdei.evidence e
            WHERE e.merchant_id = :merchantId
            GROUP BY e.type, e.status
            """, nativeQuery = true)
    List<Object[]> countByTypeAndStatus(@Param("merchantId") String merchantId);

    /** Integrity sweep: never-verified or previously failed artifacts, oldest first. */
    @Query("""
            SELECT e FROM EvidenceEntity e
            WHERE e.objectKey IS NOT NULL
              AND (e.integrityVerifiedAt IS NULL OR e.integrityOk = FALSE)
            ORDER BY e.createdAt ASC
            """)
    List<EvidenceEntity> findNeedingIntegrityCheck(Pageable pageable);
}
