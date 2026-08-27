package com.laserpay.pdei.api.service;

import com.laserpay.pdei.api.dto.MerchantResponse;
import com.laserpay.pdei.api.dto.MerchantSummaryResponse;
import com.laserpay.pdei.api.dto.PageResponse;
import com.laserpay.pdei.api.support.Rows;
import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.repository.DisputeCaseRepository;
import com.laserpay.pdei.persistence.repository.DisputeRepository;
import com.laserpay.pdei.persistence.repository.EvidenceRepository;
import com.laserpay.pdei.persistence.repository.MerchantRepository;
import com.laserpay.pdei.persistence.repository.ReadinessGapRepository;
import com.laserpay.pdei.persistence.repository.TransactionRepository;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /merchants} routes: the directory and the control-tower KPI block.
 *
 * <p>Read only. The gateway never creates or edits a merchant; merchants arrive through ingestion
 * and the simulator.</p>
 */
@Service
@Transactional(readOnly = true)
public class MerchantQueryService {

    /** Dispute statuses that count as "open" for the KPI: everything not yet resolved. */
    private static final Set<DisputeStatus> OPEN_DISPUTE_STATUSES = EnumSet.of(
            DisputeStatus.OPEN,
            DisputeStatus.EVIDENCE_GATHERING,
            DisputeStatus.UNDER_INVESTIGATION,
            DisputeStatus.AWAITING_HUMAN_REVIEW,
            DisputeStatus.REPRESENTMENT_PREPARED,
            DisputeStatus.SUBMITTED);

    /** Bands that feed the at-risk feed. */
    private static final Set<ReadinessBand> AT_RISK_BANDS =
            EnumSet.of(ReadinessBand.AT_RISK, ReadinessBand.NOT_READY);

    private final MerchantRepository merchants;
    private final TransactionRepository transactions;
    private final DisputeRepository disputes;
    private final DisputeCaseRepository cases;
    private final EvidenceRepository evidence;
    private final ReadinessGapRepository gaps;
    private final Clocks clock;

    public MerchantQueryService(MerchantRepository merchants,
                                TransactionRepository transactions,
                                DisputeRepository disputes,
                                DisputeCaseRepository cases,
                                EvidenceRepository evidence,
                                ReadinessGapRepository gaps,
                                Clocks clock) {
        this.merchants = merchants;
        this.transactions = transactions;
        this.disputes = disputes;
        this.cases = cases;
        this.evidence = evidence;
        this.gaps = gaps;
        this.clock = clock;
    }

    public PageResponse<MerchantResponse> list(Pageable pageable) {
        Page<MerchantEntity> page = merchants.findAll(pageable);
        return PageResponse.of(page, MerchantResponse::from);
    }

    public MerchantResponse get(String merchantId) {
        return MerchantResponse.from(require(merchantId));
    }

    /**
     * The control-tower KPI block.
     *
     * <p>Each figure is one aggregate query. They are issued separately rather than joined into a
     * single wide query because they hit five unrelated tables with different indexes, and a
     * hand-rolled join across them would be both slower and impossible to reuse.</p>
     */
    public MerchantSummaryResponse summary(String merchantId) {
        MerchantEntity merchant = require(merchantId);

        Map<ReadinessBand, Long> bandDistribution =
                Rows.toEnumCounts(merchants.readinessBandDistribution(merchantId), ReadinessBand.class);
        Map<EvidenceStatus, Long> evidenceByStatus = Rows.toEnumCountsFromSecondColumn(
                evidence.countByTypeAndStatus(merchantId), EvidenceStatus.class);
        Map<CaseStatus, Long> casesByStatus =
                Rows.toEnumCounts(cases.countByStatus(merchantId), CaseStatus.class);

        long totalTransactions = bandDistribution.values().stream().mapToLong(Long::longValue).sum();
        long atRisk = AT_RISK_BANDS.stream()
                .mapToLong(band -> bandDistribution.getOrDefault(band, 0L))
                .sum();
        long openDisputes = OPEN_DISPUTE_STATUSES.stream()
                .mapToLong(status -> disputes.countByMerchantIdAndStatus(merchantId, status))
                .sum();
        long expiringEvidence = evidenceByStatus.getOrDefault(EvidenceStatus.EXPIRING, 0L);
        long casesRequiringReview = casesByStatus.getOrDefault(CaseStatus.AWAITING_APPROVAL, 0L);
        long blockingGaps =
                gaps.countByMerchantIdAndSeverityAndResolvedFalse(merchantId, GapSeverity.CRITICAL)
                        + gaps.countByMerchantIdAndSeverityAndResolvedFalse(merchantId, GapSeverity.HIGH);

        return new MerchantSummaryResponse(
                merchant.getId(),
                merchant.getDisplayName(),
                merchant.getDefaultCurrency(),
                totalTransactions,
                averageScore(bandDistribution, totalTransactions),
                dominantBand(bandDistribution),
                bandDistribution,
                evidenceByStatus,
                casesByStatus,
                openDisputes,
                atRisk,
                expiringEvidence,
                casesRequiringReview,
                blockingGaps,
                clock.now());
    }

    /** Present so other services can 404 consistently on an unknown merchant. */
    public MerchantEntity require(String merchantId) {
        return merchants.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("MERCHANT", merchantId));
    }

    public boolean exists(String merchantId) {
        return merchantId != null && merchants.existsById(merchantId);
    }

    /**
     * A band-weighted approximation of the merchant's average readiness score.
     *
     * <p>Deliberately an approximation, and labelled as one: the exact mean would need a
     * {@code SUM(readiness_score)} aggregate that no repository exposes, and adding a query to
     * platform-persistence from here is out of this module's scope. Each band contributes its
     * midpoint, which is stable, monotonic in the distribution, and never claims more precision than
     * the band boundaries carry. Null when the merchant has no scored transactions at all, so the UI
     * shows "no data" rather than a confident zero.</p>
     */
    private static Integer averageScore(Map<ReadinessBand, Long> distribution, long total) {
        if (total <= 0) {
            return null;
        }
        long weighted = distribution.getOrDefault(ReadinessBand.READY, 0L) * 95
                + distribution.getOrDefault(ReadinessBand.NEARLY_READY, 0L) * 82
                + distribution.getOrDefault(ReadinessBand.AT_RISK, 0L) * 62
                + distribution.getOrDefault(ReadinessBand.NOT_READY, 0L) * 25;
        return (int) Math.round(weighted / (double) total);
    }

    private static ReadinessBand dominantBand(Map<ReadinessBand, Long> distribution) {
        return distribution.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
