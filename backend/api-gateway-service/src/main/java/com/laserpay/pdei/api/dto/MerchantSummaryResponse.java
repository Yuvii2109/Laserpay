package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.ReadinessBand;
import java.time.Instant;
import java.util.Map;

/**
 * Control-tower KPIs: {@code GET /merchants/{merchantId}/summary}.
 *
 * <p>These are exactly the five blocks the reference document asks the Merchant Control Tower to
 * show (evidence readiness, open disputes, at-risk transactions, expiring evidence, cases requiring
 * review), plus the distributions the charts need.</p>
 *
 * <p>Every figure is a count. There is deliberately no aggregated money total here: summing
 * {@code amount_minor} across disputes requires a currency-aware aggregate the repository layer does
 * not expose, and a single number that silently mixes currencies would be worse than no number.</p>
 *
 * @param atRiskTransactions transactions in the AT_RISK or NOT_READY band: the at-risk feed count
 * @param expiringEvidence   evidence in EXPIRING status: the "-5 per expiring mandatory" pipeline
 * @param casesRequiringReview cases in AWAITING_APPROVAL: the human queue depth
 * @param blockingGaps       unresolved gaps of HIGH or CRITICAL severity
 */
public record MerchantSummaryResponse(
        String merchantId,
        String displayName,
        String defaultCurrency,
        long transactions,
        Integer averageReadinessScore,
        ReadinessBand dominantBand,
        Map<ReadinessBand, Long> readinessDistribution,
        Map<EvidenceStatus, Long> evidenceByStatus,
        Map<CaseStatus, Long> casesByStatus,
        long openDisputes,
        long atRiskTransactions,
        long expiringEvidence,
        long casesRequiringReview,
        long blockingGaps,
        Instant generatedAt) {

    public MerchantSummaryResponse {
        readinessDistribution = readinessDistribution == null ? Map.of() : Map.copyOf(readinessDistribution);
        evidenceByStatus = evidenceByStatus == null ? Map.of() : Map.copyOf(evidenceByStatus);
        casesByStatus = casesByStatus == null ? Map.of() : Map.copyOf(casesByStatus);
    }
}
