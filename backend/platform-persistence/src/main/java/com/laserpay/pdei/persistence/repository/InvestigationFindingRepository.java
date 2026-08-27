package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.InvestigationFindingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Per-claim findings. Rows with {@code validated = false} are unsupported claims: they force
 * SafetyDecision.DENY and increment {@code pdei_ai_unsupported_claims_total}.
 */
@Repository
public interface InvestigationFindingRepository extends JpaRepository<InvestigationFindingEntity, String> {

    List<InvestigationFindingEntity> findByInvestigationIdOrderBySequenceNoAsc(String investigationId);

    List<InvestigationFindingEntity> findByInvestigationIdAndValidatedFalse(String investigationId);

    List<InvestigationFindingEntity> findByInvestigationIdAndFindingType(String investigationId, String findingType);

    List<InvestigationFindingEntity> findByEvidenceId(String evidenceId);

    long countByInvestigationIdAndValidatedFalse(String investigationId);
}
