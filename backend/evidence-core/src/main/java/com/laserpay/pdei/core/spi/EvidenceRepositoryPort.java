package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.SearchPage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read/write port for {@code pdei.evidence}, {@code evidence_versions}, {@code evidence_relationships}. */
public interface EvidenceRepositoryPort {

    Optional<EvidenceView> findById(String evidenceId);

    List<EvidenceView> findByIds(Collection<String> evidenceIds);

    /** Ids that actually exist - the primitive behind safety rule 1. */
    Set<String> existingIds(Collection<String> evidenceIds);

    List<EvidenceView> findByTransactionId(String transactionId);

    List<EvidenceView> findByTransactionIdAndStatusIn(String transactionId, Collection<EvidenceStatus> statuses);

    List<EvidenceView> findByMerchantIdAndTypeAndStatus(String merchantId, EvidenceType type, EvidenceStatus status);

    /** Content-address lookup used to make evidence creation idempotent. */
    Optional<EvidenceView> findByShaAndTransactionId(String sha256, String transactionId);

    /** Evidence whose {@code expires_at} falls inside a window, for the nightly expiry sweep. */
    List<EvidenceView> findExpiringBetween(Instant from, Instant to, int limit);

    void insert(EvidenceView evidence);

    /** Status transition plus {@code updated_at}; returns false when the row is gone. */
    boolean updateStatus(String evidenceId, EvidenceStatus status, Instant at, String reason);

    void updateSummaryAndQuality(String evidenceId, String summary, double qualityScore, boolean provenanceVerified);

    void insertVersion(EvidenceVersionRecord version);

    List<EvidenceVersionRecord> findVersions(String evidenceId);

    void insertRelationship(EvidenceRelationship relationship);

    List<EvidenceRelationship> findRelationships(String evidenceId);

    /** Direct children in the version chain (rows whose {@code parent_evidence_id} is this id). */
    List<EvidenceView> findChildren(String evidenceId);

    /** Postgres full-text search over the tsvector column added by {@code V10__fts.sql}. */
    SearchPage<EvidenceView> search(String tsQuery, String merchantId, EvidenceType type,
                                    EvidenceStatus status, String transactionId, int page, int size);

    /** {@code pdei_evidence_total{type,status}} support and the control-tower KPI tiles. */
    long countByMerchantAndStatus(String merchantId, EvidenceStatus status);
}
