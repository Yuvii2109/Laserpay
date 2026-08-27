package com.laserpay.pdei.orchestrator.model;

/**
 * Argument of activity 7, {@code validateAndGate}.
 *
 * <p>Only the investigation id travels: the activity re-reads the stored
 * {@code InvestigationResult} from Postgres rather than judging a copy the workflow was holding.
 * The gate must run against the artifact that was actually persisted, or the audit trail would
 * record a decision about something else.</p>
 */
public record GateRequest(
        CaseRef ref,
        String investigationId,
        boolean aiUsed,
        String idempotencyToken) {
}
