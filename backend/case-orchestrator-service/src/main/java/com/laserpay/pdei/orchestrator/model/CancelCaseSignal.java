package com.laserpay.pdei.orchestrator.model;

import java.time.Instant;

/**
 * Payload of the {@code cancelCase} signal.
 *
 * <p>Cancellation is a graceful path, not an abort: the workflow finishes the activity it is on,
 * runs {@code closeCase} with {@link CaseResolution#CANCELLED} and returns normally. Compensation
 * does <em>not</em> run, because nothing went wrong - an operator simply decided the case should
 * stop. Terminating the workflow outright is a separate, blunter operation exposed at
 * {@code POST /orchestrator/v1/cases/{caseId}/terminate}.</p>
 */
public record CancelCaseSignal(
        String reason,
        String actor,
        Instant requestedAt) {

    public static CancelCaseSignal of(String reason, String actor) {
        return new CancelCaseSignal(reason, actor, null);
    }
}
