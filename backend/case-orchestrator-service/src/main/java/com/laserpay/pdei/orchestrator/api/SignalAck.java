package com.laserpay.pdei.orchestrator.api;

import java.time.Instant;

/**
 * Response to any signal route.
 *
 * <p>{@code delivered = false} is a 200, not an error: it means no workflow was running for that
 * case - normally because it already closed. The caller is told plainly rather than being handed a
 * 500 for a race it cannot avoid.</p>
 */
public record SignalAck(
        String caseId,
        String signal,
        boolean delivered,
        String detail,
        Instant at) {

    public static SignalAck of(String caseId, String signal, boolean delivered, Instant at) {
        return new SignalAck(caseId, signal, delivered, delivered
                ? "signal delivered to the running workflow"
                : "no running workflow for this case; the signal was not delivered", at);
    }
}
