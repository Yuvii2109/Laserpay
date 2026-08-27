package com.laserpay.pdei.core.spi;

import com.laserpay.pdei.common.domain.InvestigationClassification;
import com.laserpay.pdei.common.domain.RecommendedAction;
import com.laserpay.pdei.common.domain.SafetyDecision;

import java.time.Instant;

/**
 * One row of {@code pdei.investigations}: what the AI proposed and what the deterministic gate did
 * with it. Both halves are stored so the decision is reconstructable months later.
 */
public record InvestigationRecord(
        String investigationId,
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        InvestigationClassification classification,
        double confidence,
        RecommendedAction recommendedAction,
        SafetyDecision safetyDecision,
        String provider,
        String model,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        int attempt,
        String reasoningSummary,
        String narrative,
        String resultJson,
        String verdictJson,
        Instant startedAt,
        Instant completedAt) {
}
