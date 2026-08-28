package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.EvidenceRelationshipEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Edges of the evidence graph (nodes are {@code pdei.evidence} rows). */
@Repository
public interface EvidenceRelationshipRepository extends JpaRepository<EvidenceRelationshipEntity, String> {

    List<EvidenceRelationshipEntity> findByFromEvidenceId(String fromEvidenceId);

    List<EvidenceRelationshipEntity> findByToEvidenceId(String toEvidenceId);

    List<EvidenceRelationshipEntity> findByFromEvidenceIdOrToEvidenceId(String fromEvidenceId, String toEvidenceId);

    List<EvidenceRelationshipEntity> findByRelationshipType(String relationshipType);

    Optional<EvidenceRelationshipEntity> findByFromEvidenceIdAndToEvidenceIdAndRelationshipType(
            String fromEvidenceId, String toEvidenceId, String relationshipType);

    List<EvidenceRelationshipEntity> findByFromEvidenceIdInOrToEvidenceIdIn(
            Collection<String> fromIds, Collection<String> toIds);

    /**
     * Every edge whose endpoints belong to the given transaction - one query for the whole
     * graph payload of {@code GET /transactions/{transactionId}/graph}.
     */
    @Query(value = """
            SELECT r.* FROM pdei.evidence_relationships r
            JOIN pdei.evidence ef ON ef.evidence_id = r.from_evidence_id
            JOIN pdei.evidence et ON et.evidence_id = r.to_evidence_id
            WHERE ef.transaction_id = :transactionId OR et.transaction_id = :transactionId
            """, nativeQuery = true)
    List<EvidenceRelationshipEntity> findByTransactionId(@Param("transactionId") String transactionId);

    /** Contradiction count for a transaction - a direct input to the safety gate. */
    @Query(value = """
            SELECT count(*) FROM pdei.evidence_relationships r
            JOIN pdei.evidence ef ON ef.evidence_id = r.from_evidence_id
            WHERE r.relationship_type = 'CONTRADICTS' AND ef.transaction_id = :transactionId
            """, nativeQuery = true)
    long countContradictionsForTransaction(@Param("transactionId") String transactionId);
}
