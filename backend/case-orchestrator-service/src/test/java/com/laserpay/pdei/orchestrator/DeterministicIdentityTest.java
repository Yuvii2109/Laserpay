package com.laserpay.pdei.orchestrator;

import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.orchestrator.activity.CaseActivitiesImpl;
import com.laserpay.pdei.orchestrator.listener.CaseIdResolver;
import com.laserpay.pdei.orchestrator.model.CasePhase;
import com.laserpay.pdei.orchestrator.model.CaseTimers;
import com.laserpay.pdei.orchestrator.model.DisputeCaseInput;
import com.laserpay.pdei.orchestrator.submission.NetworkSubmissionRequest;
import com.laserpay.pdei.orchestrator.submission.NetworkSubmissionResult;
import com.laserpay.pdei.orchestrator.submission.SimulatedNetworkSubmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity rules the whole duplicate-tolerance story rests on.
 *
 * <p>Three things must be pure functions of their inputs, or the platform's idempotency guarantees
 * quietly stop holding: the case id derived from a dispute, the event id derived from an
 * idempotency key, and the submission reference derived from a package. Each is asserted here
 * rather than assumed.</p>
 */
class DeterministicIdentityTest {

    private static final Instant FIXED = Instant.parse("2026-08-26T10:15:30Z");

    @Test
    @DisplayName("a dispute id always derives the same case id, and it carries the CASE- prefix")
    void caseIdIsDeterministic() {
        String first = CaseIdResolver.derive("DSP-0000ABCD");
        String second = CaseIdResolver.derive("DSP-0000ABCD");

        assertThat(first).isEqualTo(second)
                .startsWith("CASE-")
                .hasSize("CASE-".length() + CaseIdResolver.DIGEST_LENGTH);
        assertThat(CaseIdResolver.derive("DSP-0000ABCE")).isNotEqualTo(first);

        // ...and the workflow id round-trips, which is what the signal routes depend on.
        String workflowId = DisputeCaseInput.workflowIdFor(first);
        assertThat(workflowId).isEqualTo("case-" + first);
        assertThat(DisputeCaseInput.caseIdFromWorkflowId(workflowId)).isEqualTo(first);
    }

    @Test
    @DisplayName("the same idempotency key always produces the same event id")
    void eventIdIsDeterministic() {
        String key = "CaseSubmitted:CASE-0001:v1";

        String first = CaseActivitiesImpl.deterministicEventId(key);
        assertThat(CaseActivitiesImpl.deterministicEventId(key)).isEqualTo(first);
        assertThat(CaseActivitiesImpl.deterministicEventId(key + "x")).isNotEqualTo(first);
        // Must still be a UUID: the canonical envelope declares eventId as a uuid string.
        assertThat(java.util.UUID.fromString(first)).hasToString(first);
    }

    @Test
    @DisplayName("resubmitting the same bundle produces the same simulated network reference")
    void submissionReferenceIsDeterministic() {
        SimulatedNetworkSubmitter submitter =
                new SimulatedNetworkSubmitter(Clocks.fixed(FIXED), null);
        NetworkSubmissionRequest request = request("0a".repeat(32), 1);

        NetworkSubmissionResult first = submitter.submit(request);
        NetworkSubmissionResult retry = submitter.submit(request);

        assertThat(first.accepted()).isTrue();
        assertThat(first.simulated()).isTrue();
        assertThat(first.submitterName()).isEqualTo(SimulatedNetworkSubmitter.NAME);
        assertThat(first.networkReference()).startsWith(SimulatedNetworkSubmitter.REFERENCE_PREFIX);
        assertThat(retry.networkReference()).isEqualTo(first.networkReference());
        assertThat(retry.submissionId()).isEqualTo(first.submissionId());

        // A different package version, or different bytes, is a different submission.
        assertThat(submitter.submit(request("0a".repeat(32), 2)).networkReference())
                .isNotEqualTo(first.networkReference());
        assertThat(submitter.submit(request("0b".repeat(32), 1)).networkReference())
                .isNotEqualTo(first.networkReference());
    }

    @Test
    @DisplayName("a package with no verifiable bundle is refused rather than 'submitted'")
    void unverifiablePackageIsRefused() {
        SimulatedNetworkSubmitter submitter =
                new SimulatedNetworkSubmitter(Clocks.fixed(FIXED), null);

        NetworkSubmissionResult result = submitter.submit(
                new NetworkSubmissionRequest("CASE-0001", "DSP-0001", "MER-0001", "TX-0001", null,
                        1, null, null, 0L, 0, "tester"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.networkReference()).isNull();
        assertThat(result.statusDetail()).contains("bundle object key");
    }

    @Test
    @DisplayName("timers fall back to the contract defaults rather than to zero")
    void timersFallBackToContractDefaults() {
        CaseTimers partial = new CaseTimers(null, Duration.ZERO, Duration.ofHours(6), null, null,
                Duration.ofSeconds(-1), 0, 0);
        CaseTimers resolved = CaseTimers.orDefaults(partial);

        assertThat(resolved.missingEvidenceWait()).isEqualTo(Duration.ofDays(7));
        assertThat(resolved.evidenceWaitSlice()).isEqualTo(CaseTimers.DEFAULT_EVIDENCE_WAIT_SLICE);
        // An explicitly configured value survives.
        assertThat(resolved.humanApprovalTimeout()).isEqualTo(Duration.ofHours(6));
        assertThat(resolved.followUpMaxDuration()).isEqualTo(CaseTimers.DEFAULT_FOLLOW_UP_MAX_DURATION);
        assertThat(resolved.maxAssessmentRounds()).isEqualTo(CaseTimers.DEFAULT_MAX_ASSESSMENT_ROUNDS);
        assertThat(CaseTimers.orDefaults(null)).isEqualTo(CaseTimers.defaults());
    }

    @Test
    @DisplayName("phase progress is monotonic across the twelve contract steps")
    void phaseProgressIsMonotonic() {
        assertThat(CasePhase.OPENING.percent()).isLessThan(CasePhase.AWAITING_EVIDENCE.percent());
        assertThat(CasePhase.AWAITING_EVIDENCE.percent())
                .isLessThan(CasePhase.AWAITING_APPROVAL.percent());
        assertThat(CasePhase.AWAITING_APPROVAL.percent()).isLessThan(CasePhase.SUBMITTING.percent());
        assertThat(CasePhase.CLOSED.percent()).isEqualTo(100);
        assertThat(CasePhase.FAILED.percent()).isEqualTo(100);
        assertThat(CasePhase.CANCELLED.isTerminal()).isTrue();
        assertThat(CasePhase.FOLLOW_UP.isWaiting()).isTrue();
        assertThat(CasePhase.GATING.isWaiting()).isFalse();
    }

    private static NetworkSubmissionRequest request(String sha256, int version) {
        return new NetworkSubmissionRequest("CASE-0001", "DSP-0001", "MER-0001", "TX-0001",
                "NET-REF-1", version,
                "MER-0001/CASE-0001/representment-CASE-0001-v" + version + ".zip", sha256, 4096L, 3,
                "tester");
    }

    /** Kept so the money rule is asserted somewhere in this module too: minor units only. */
    @Test
    @DisplayName("dispute money stays in minor units on the workflow boundary")
    void moneyStaysInMinorUnits() {
        DisputeCaseInput input = DisputeCaseInput.start("CASE-0001", "DSP-0001", "MER-0001",
                "TX-0001", com.laserpay.pdei.common.domain.DisputeReasonCode.GOODS_NOT_RECEIVED,
                Money.of(1_299_900L, "INR"), FIXED, FIXED.plus(Duration.ofDays(30)), "corr", "evt",
                "tester", CaseTimers.defaults());

        assertThat(input.amountMinor()).isEqualTo(1_299_900L);
        assertThat(input.currency()).isEqualTo("INR");
        assertThat(input.disputeAmount()).isEqualTo(Money.of(1_299_900L, "INR"));
    }
}
