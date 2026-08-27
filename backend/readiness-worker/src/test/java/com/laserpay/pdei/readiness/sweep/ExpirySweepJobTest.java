package com.laserpay.pdei.readiness.sweep;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.readiness.config.ReadinessProperties;
import com.laserpay.pdei.readiness.publish.ReadinessEventPublisher;
import com.laserpay.pdei.readiness.recompute.RecomputeDebouncer;
import com.laserpay.pdei.readiness.recompute.RecomputeRequest;
import com.laserpay.pdei.readiness.recompute.RecomputeTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expiry lifecycle: ACTIVE becomes EXPIRING as the retention window closes, then EXPIRED once it
 * has passed, and each transition tells the rest of the platform.
 *
 * <p>These are real state transitions on financial evidence, so they are tested against a store
 * double that enforces the same compare-and-set semantics as the database, not against a mock that
 * agrees with whatever the job asks for.
 */
class ExpirySweepJobTest {

    private static final Instant NOW = Instant.parse("2026-08-26T02:15:00Z");
    private static final String TX_EXPIRED = "TX-000000000001";
    private static final String TX_EXPIRING = "TX-000000000002";

    private InMemoryEvidenceExpiryStore store;
    private RecordingEventPublisher events;
    private ReadinessEventPublisher publisher;
    private ReadinessProperties properties;
    private ScheduledExecutorService scheduler;
    private RecomputeDebouncer debouncer;
    private List<RecomputeRequest> queued;

    @BeforeEach
    void setUp() {
        store = new InMemoryEvidenceExpiryStore();
        events = new RecordingEventPublisher();
        publisher = new ReadinessEventPublisher(events, Clocks.fixed(NOW));
        properties = new ReadinessProperties();
        properties.getSweep().setBatchSize(50);
        properties.getSweep().setWarningDays(7);

        queued = new CopyOnWriteArrayList<>();
        scheduler = Executors.newScheduledThreadPool(1);
        // A very long debounce keeps the requests pending so the test can inspect them without
        // racing the scheduler.
        properties.setDebounce(Duration.ofMinutes(10));
        properties.setMaxDebounceDelay(Duration.ofMinutes(30));
        debouncer = new RecomputeDebouncer(scheduler, Runnable::run, queued::add,
                Clocks.fixed(NOW), properties, null);
    }

    @AfterEach
    void tearDown() {
        debouncer.close();
        scheduler.shutdownNow();
    }

    private ExpirySweepJob job() {
        return new ExpirySweepJob(store, publisher, debouncer, properties, null, Clocks.fixed(NOW));
    }

    @Test
    @DisplayName("evidence past its expiry becomes EXPIRED and publishes EvidenceExpired")
    void expiresEvidencePastItsWindow() {
        store.add("EV-00000001", TX_EXPIRED, EvidenceStatus.ACTIVE, NOW.minus(Duration.ofDays(1)));

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(store.statusOf("EV-00000001")).isEqualTo(EvidenceStatus.EXPIRED);

        List<RecordingEventPublisher.Published> published = events.events();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).topic()).isEqualTo(Topics.EVIDENCE_EVENTS);
        assertThat(published.get(0).event().eventType()).isEqualTo(EventType.EvidenceExpired);
        assertThat(published.get(0).event().aggregateId()).isEqualTo("EV-00000001");
        // Provenance survives the transition: the event that created the artifact caused its expiry.
        assertThat(published.get(0).event().causationId()).isEqualTo("evt-source-EV-00000001");
        assertThat(events.auditEvents()).singleElement()
                .satisfies(audit -> assertThat(audit.action()).isEqualTo("EVIDENCE_EXPIRED"));
    }

    @Test
    @DisplayName("evidence entering the warning window becomes EXPIRING without an event")
    void marksEvidenceExpiringInsideTheWarningWindow() {
        store.add("EV-00000002", TX_EXPIRING, EvidenceStatus.ACTIVE, NOW.plus(Duration.ofDays(3)));

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.expired()).isZero();
        assertThat(result.markedExpiring()).isEqualTo(1);
        assertThat(store.statusOf("EV-00000002")).isEqualTo(EvidenceStatus.EXPIRING);
        // The contract defines no "EvidenceExpiring" event type, so none is invented; the change
        // still reaches the world through the recomputation and the audit trail.
        assertThat(events.events()).isEmpty();
        assertThat(events.auditEvents()).singleElement()
                .satisfies(audit -> assertThat(audit.action()).isEqualTo("EVIDENCE_EXPIRING"));
    }

    @Test
    @DisplayName("evidence outside the warning window is left alone")
    void leavesEvidenceOutsideTheWindowUntouched() {
        store.add("EV-00000003", TX_EXPIRING, EvidenceStatus.ACTIVE, NOW.plus(Duration.ofDays(90)));

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.changedNothing()).isTrue();
        assertThat(store.statusOf("EV-00000003")).isEqualTo(EvidenceStatus.ACTIVE);
    }

    @Test
    @DisplayName("INVALIDATED and SUPERSEDED evidence is never relabelled as expired")
    void terminalStatusesAreNotTouched() {
        store.add("EV-00000004", TX_EXPIRED, EvidenceStatus.INVALIDATED, NOW.minus(Duration.ofDays(5)));
        store.add("EV-00000005", TX_EXPIRED, EvidenceStatus.SUPERSEDED, NOW.minus(Duration.ofDays(5)));

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.changedNothing()).isTrue();
        assertThat(store.statusOf("EV-00000004")).isEqualTo(EvidenceStatus.INVALIDATED);
        assertThat(store.statusOf("EV-00000005")).isEqualTo(EvidenceStatus.SUPERSEDED);
    }

    @Test
    @DisplayName("every touched transaction is queued for recomputation exactly once")
    void queuesAffectedTransactionsForRecomputation() {
        store.add("EV-00000006", TX_EXPIRED, EvidenceStatus.ACTIVE, NOW.minus(Duration.ofDays(1)));
        store.add("EV-00000007", TX_EXPIRED, EvidenceStatus.PENDING, NOW.minus(Duration.ofDays(2)));
        store.add("EV-00000008", TX_EXPIRING, EvidenceStatus.ACTIVE, NOW.plus(Duration.ofDays(2)));

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.markedExpiring()).isEqualTo(1);
        // Two artifacts on the same transaction, one recomputation for it.
        assertThat(result.transactionsQueued()).isEqualTo(2);
        assertThat(debouncer.pendingCount()).isEqualTo(2);
        assertThat(debouncer.peek(TX_EXPIRED).trigger()).isEqualTo(RecomputeTrigger.NIGHTLY_SWEEP);
        assertThat(debouncer.peek(TX_EXPIRING).trigger()).isEqualTo(RecomputeTrigger.NIGHTLY_SWEEP);
    }

    @Test
    @DisplayName("running the sweep twice transitions each artifact exactly once")
    void sweepIsIdempotent() {
        store.add("EV-00000009", TX_EXPIRED, EvidenceStatus.ACTIVE, NOW.minus(Duration.ofDays(1)));

        ExpirySweepJob job = job();
        ExpirySweepJob.SweepResult first = job.sweep();
        ExpirySweepJob.SweepResult second = job.sweep();

        assertThat(first.expired()).isEqualTo(1);
        assertThat(second.changedNothing()).isTrue();
        assertThat(store.transitions()).containsExactly("EV-00000009:EXPIRED");
        assertThat(events.ofType(EventType.EvidenceExpired)).hasSize(1);
    }

    @Test
    @DisplayName("losing the race to another worker publishes nothing and is not an error")
    void concurrentWorkerWinsWithoutDoublePublishing() {
        store.add("EV-00000010", TX_EXPIRED, EvidenceStatus.ACTIVE, NOW.minus(Duration.ofDays(1)));
        store.contend("EV-00000010");

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.expired()).isZero();
        assertThat(store.statusOf("EV-00000010")).isEqualTo(EvidenceStatus.EXPIRED);
        assertThat(events.events()).isEmpty();
    }

    @Test
    @DisplayName("evidence with no transaction expires but queues no recomputation")
    void merchantLevelEvidenceHasNothingToRescore() {
        store.add("EV-00000011", null, EvidenceStatus.ACTIVE, NOW.minus(Duration.ofDays(1)));

        ExpirySweepJob.SweepResult result = job().sweep();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.transactionsQueued()).isZero();
        assertThat(debouncer.pendingCount()).isZero();
    }
}
