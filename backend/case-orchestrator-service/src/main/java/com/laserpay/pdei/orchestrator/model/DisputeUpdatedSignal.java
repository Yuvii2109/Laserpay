package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.DisputeStatus;

import java.time.Instant;

/**
 * Payload of the {@code disputeUpdated} signal, fed by {@code DisputeUpdated} and
 * {@code DisputeClosed} events on {@code pdei.dispute.events.v1}.
 *
 * <p>A terminal {@link DisputeStatus} is what ends the step 11 follow-up loop. The workflow treats
 * this signal as late, out-of-order and duplicate-prone: it only ever moves the dispute status
 * forward into a terminal state and ignores a stale non-terminal update that arrives after one.</p>
 *
 * @param eventId the canonical event that produced this signal, so a duplicate delivery is visible
 */
public record DisputeUpdatedSignal(
        DisputeStatus status,
        String eventId,
        String reason,
        Instant occurredAt) {

    public static DisputeUpdatedSignal of(DisputeStatus status, String eventId) {
        return new DisputeUpdatedSignal(status, eventId, null, null);
    }

    /** True when the dispute has reached an outcome and the case has nothing left to wait for. */
    public boolean isTerminal() {
        return status == DisputeStatus.WON || status == DisputeStatus.LOST
                || status == DisputeStatus.EXPIRED || status == DisputeStatus.WITHDRAWN;
    }
}
