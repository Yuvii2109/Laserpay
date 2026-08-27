package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.InvestigationResponse;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.model.InvestigationResult;
import com.laserpay.pdei.core.model.ModelMetadata;
import com.laserpay.pdei.core.model.SafetyVerdict;
import com.laserpay.pdei.core.spi.CaseRepositoryPort;
import com.laserpay.pdei.core.spi.InvestigationRecord;
import com.laserpay.pdei.persistence.entity.InvestigationFindingEntity;
import com.laserpay.pdei.persistence.repository.InvestigationFindingRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /investigations/{investigationId}}.
 *
 * <p>The stored row keeps the model's answer and the gate's verdict as JSON documents. They are
 * parsed back into {@code InvestigationResult} and {@code SafetyVerdict} here so the frontend and
 * the Python service see the identical field names on both sides of the wire, rather than an opaque
 * string the browser would have to parse a second time.</p>
 *
 * <p>Parsing is best effort. A row written by a newer schema version must still render its headline
 * fields, so a failure to parse the nested document downgrades to null instead of failing the whole
 * request: the operator still sees the classification, the confidence and the safety decision.</p>
 */
@Service
@Transactional(readOnly = true)
public class InvestigationQueryService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationQueryService.class);

    private static final String ENTITY_TYPE = "INVESTIGATION";

    private final CaseRepositoryPort cases;
    private final InvestigationFindingRepository findings;

    public InvestigationQueryService(CaseRepositoryPort cases, InvestigationFindingRepository findings) {
        this.cases = cases;
        this.findings = findings;
    }

    public InvestigationResponse get(String investigationId) {
        InvestigationRecord record = cases.findInvestigation(investigationId)
                .orElseThrow(() -> new NotFoundException(ENTITY_TYPE, investigationId));
        return toResponse(record);
    }

    /** The latest investigation for a case, used by the Case X-Ray AI panel. */
    public InvestigationResponse latestForCase(String caseId) {
        InvestigationRecord record = cases.findLatestInvestigationForCase(caseId)
                .orElseThrow(() -> new NotFoundException(ENTITY_TYPE, "for case " + caseId));
        return toResponse(record);
    }

    private InvestigationResponse toResponse(InvestigationRecord record) {
        InvestigationResult result = parse(record.resultJson(), InvestigationResult.class,
                record.investigationId(), "result");
        SafetyVerdict verdict = parse(record.verdictJson(), SafetyVerdict.class,
                record.investigationId(), "verdict");
        ModelMetadata metadata = new ModelMetadata(
                record.provider(), record.model(), record.promptTokens(), record.completionTokens(),
                record.latencyMs(), record.attempt());

        return new InvestigationResponse(
                record.investigationId(),
                record.caseId(),
                record.disputeId(),
                record.merchantId(),
                record.transactionId(),
                record.classification(),
                record.confidence(),
                record.recommendedAction(),
                record.safetyDecision(),
                metadata.isDeterministic(),
                record.reasoningSummary(),
                record.narrative(),
                result,
                verdict,
                findingViews(record.investigationId()),
                metadata,
                record.startedAt(),
                record.completedAt());
    }

    private List<InvestigationResponse.FindingView> findingViews(String investigationId) {
        List<InvestigationFindingEntity> rows =
                findings.findByInvestigationIdOrderBySequenceNoAsc(investigationId);
        return rows.stream()
                .map(row -> new InvestigationResponse.FindingView(
                        row.getId(),
                        row.getSequenceNo(),
                        row.getFindingType(),
                        row.getEvidenceId(),
                        row.getRelatedEvidenceId(),
                        row.getField(),
                        row.getClaim(),
                        row.getDetail(),
                        row.isValidated(),
                        row.getValidationError()))
                .toList();
    }

    private static <T> T parse(String json, Class<T> type, String investigationId, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.mapper().readValue(json, type);
        } catch (Exception e) {
            log.warn("Investigation {} has an unparseable {} document: {}",
                    investigationId, field, e.toString());
            return null;
        }
    }
}
