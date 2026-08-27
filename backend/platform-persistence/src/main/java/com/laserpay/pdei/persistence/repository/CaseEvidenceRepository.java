package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.CaseEvidenceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Version-pinned evidence attached to a case; drives the representment package manifest. */
@Repository
public interface CaseEvidenceRepository extends JpaRepository<CaseEvidenceEntity, String> {

    List<CaseEvidenceEntity> findByCaseIdOrderByDisplayOrderAsc(String caseId);

    List<CaseEvidenceEntity> findByCaseIdAndIncludedInPackageTrueOrderByDisplayOrderAsc(String caseId);

    List<CaseEvidenceEntity> findByEvidenceId(String evidenceId);

    Optional<CaseEvidenceEntity> findByCaseIdAndEvidenceId(String caseId, String evidenceId);

    boolean existsByCaseIdAndEvidenceId(String caseId, String evidenceId);

    long countByCaseId(String caseId);
}
