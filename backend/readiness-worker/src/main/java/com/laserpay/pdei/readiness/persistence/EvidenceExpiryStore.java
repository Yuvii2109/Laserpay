package com.laserpay.pdei.readiness.persistence;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The narrow slice of {@code pdei.evidence} the expiry sweep needs: find artifacts whose retention
 * window is closing, and move them along the lifecycle.
 *
 * <p>Only two transitions exist here, both over {@code EvidenceStatus} (PLATFORM-CONTRACT
 * section 6):
 *
 * <pre>
 *   ACTIVE                      --(expiry inside the warning window)--&gt;  EXPIRING
 *   PENDING | ACTIVE | EXPIRING --(expires_at has passed)-------------&gt;  EXPIRED
 * </pre>
 *
 * <p>INVALIDATED and SUPERSEDED are terminal for this purpose: an artifact that was rejected or
 * replaced must not be quietly relabelled as merely expired, because those three words mean very
 * different things to a scheme arbitrator.
 *
 * <p>An interface rather than a class so {@code ExpirySweepJob} - which owns real lifecycle
 * decisions about financial evidence - can be tested exhaustively against an in-memory double
 * instead of only against a live database.
 */
public interface EvidenceExpiryStore {

    /** Statuses that may still transition to EXPIRED. */
    List<EvidenceStatus> EXPIRABLE =
            List.of(EvidenceStatus.PENDING, EvidenceStatus.ACTIVE, EvidenceStatus.EXPIRING);

    /**
     * Artifacts whose {@code expires_at} has already passed and that are still in a live status.
     *
     * <p>There is deliberately no lower bound on the window: a worker that was down for a week must
     * still catch up on everything that expired while it was gone (assume late processing, rule 10).
     */
    List<ExpiringEvidence> findDueForExpiry(Instant now, int limit);

    /**
     * ACTIVE artifacts entering the warning window between {@code now} and {@code windowEnd}.
     *
     * <p>The transition to EXPIRING is what lets the readiness engine apply its -5 EXPIRING_SOON
     * penalty (contract section 7) <em>before</em> the evidence becomes useless, which is the whole
     * point of a proactive platform.
     */
    List<ExpiringEvidence> findEnteringWarningWindow(Instant now, Instant windowEnd, int limit);

    /**
     * Conditional status transition. Conditional is what makes the sweep idempotent: two workers
     * sweeping at once transition each artifact exactly once and the loser is told so.
     *
     * @return true when this call performed the transition, false when the row had already moved
     */
    boolean transition(String evidenceId, Collection<EvidenceStatus> from, EvidenceStatus to, Instant at);

    /** Current status of one artifact; verification and diagnostics. */
    EvidenceStatus statusOf(String evidenceId);

    /**
     * One artifact whose retention window is closing.
     *
     * @param sourceEventId the canonical event that produced the artifact, carried onto the emitted
     *                      {@code EvidenceExpired} event as its {@code causationId} so provenance
     *                      survives the lifecycle transition
     */
    record ExpiringEvidence(
            String evidenceId,
            String merchantId,
            String transactionId,
            EvidenceType type,
            EvidenceStatus status,
            String sourceEventId,
            Instant expiresAt) {
    }
}
