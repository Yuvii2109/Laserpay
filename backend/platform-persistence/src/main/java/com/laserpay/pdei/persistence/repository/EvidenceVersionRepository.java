package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.EvidenceVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Append-only evidence version history ({@code GET /evidence/{evidenceId}/versions} and the
 * lineage walk). Rows are inserted, never updated: the database trigger
 * {@code trg_evidence_versions_immutable} enforces it independently of JPA.
 */
@Repository
public interface EvidenceVersionRepository extends JpaRepository<EvidenceVersionEntity, String> {

    List<EvidenceVersionEntity> findByEvidenceIdOrderByVersionNumberAsc(String evidenceId);

    List<EvidenceVersionEntity> findByEvidenceIdOrderByVersionNumberDesc(String evidenceId);

    Optional<EvidenceVersionEntity> findTopByEvidenceIdOrderByVersionNumberDesc(String evidenceId);

    Optional<EvidenceVersionEntity> findByEvidenceIdAndVersionNumber(String evidenceId, int versionNumber);

    List<EvidenceVersionEntity> findBySha256(String sha256);

    long countByEvidenceId(String evidenceId);
}
