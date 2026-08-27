package com.laserpay.pdei.core.spi;

import java.time.Instant;

/**
 * One row of {@code pdei.ai_admission_log}: every admission decision, admitted or not.
 * This table is what {@code GET /api/v1/metrics/funnel} counts.
 */
public record AdmissionLogRecord(
        String admissionId,
        String caseId,
        String merchantId,
        String transactionId,
        boolean admitted,
        int priority,
        String reason,
        String shortCircuit,
        double financialImpact,
        double deadlineUrgency,
        double ambiguityScore,
        double deterministicConfidence,
        long disputeAmountMinor,
        String currency,
        Instant decidedAt) {
}
