package com.laserpay.pdei.orchestrator.model;

/**
 * The identity of a case, passed to most activities. Small on purpose: activities re-read the
 * authoritative state from Postgres rather than trusting workflow-carried copies of it.
 */
public record CaseRef(
        String caseId,
        String disputeId,
        String merchantId,
        String transactionId,
        String correlationId) {
}
