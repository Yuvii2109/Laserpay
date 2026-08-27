package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.readiness.config.ReadinessProperties;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The debounce contract: a burst of events for one transaction costs one recomputation.
 *
 * <p>This is the behaviour the whole worker is built around, so it is tested at the level where the
 * decision is actually made rather than through a Kafka listener. The scheduler is real but the
 * windows are milliseconds, and every assertion waits on an observable outcome rather than on a
 * fixed sleep.
 */
class RecomputeDebouncerTest {

    private static final String TX = "TX-000000000001";
    private static final String MERCHANT = "MER-00000001";

    private ScheduledExecutorService scheduler;
    private List<RecomputeRequest> executed;
    private RecomputeDebouncer debouncer;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        executed = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (debouncer != null) {
            debouncer.close();
        }
        scheduler.shutdownNow();
    }

    private RecomputeDebouncer debouncerWith(Duration debounce, Duration maxDelay) {
        ReadinessProperties properties = new ReadinessProperties();
        properties.setDebounce(debounce);
        properties.setMaxDebounceDelay(maxDelay);
        // Same-thread execution keeps the assertions about *what ran*, not about thread timing.
        debouncer = new RecomputeDebouncer(scheduler, Runnable::run, executed::add,
                Clocks.system(), properties, null);
        return debouncer;
    }

    @Test
    @DisplayName("a burst of events for one transaction produces exactly one recomputation")
    void burstCollapsesToOneRecomputation() throws Exception {
        RecomputeDebouncer target = debouncerWith(Duration.ofMillis(120), Duration.ofSeconds(5));

        // The fan-out of a single captured payment: five events, milliseconds apart.
        for (int i = 0; i < 5; i++) {
            target.submit(request(RecomputeTrigger.ENTITY_STATE_CHANGE, "evt-" + i));
            TimeUnit.MILLISECONDS.sleep(10);
        }

        assertThat(target.pendingCount()).isEqualTo(1);
        awaitExecutions(1);
        assertThat(executed).hasSize(1);
        assertThat(target.pendingCount()).isZero();
    }

    @Test
    @DisplayName("the surviving request keeps the most specific trigger and the earliest request time")
    void mergeKeepsTheMostSpecificTrigger() {
        RecomputeDebouncer target = debouncerWith(Duration.ofSeconds(30), Duration.ofMinutes(5));
        Instant first = Instant.parse("2026-08-26T10:00:00Z");

        target.submit(new RecomputeRequest(TX, MERCHANT, null, RecomputeTrigger.ENTITY_STATE_CHANGE,
                "evt-entity", "corr-1", first));
        target.submit(new RecomputeRequest(TX, MERCHANT, null, RecomputeTrigger.EVIDENCE_EVENT,
                "evt-evidence", null, first.plusSeconds(1)));
        target.submit(new RecomputeRequest(TX, MERCHANT, null, RecomputeTrigger.ENTITY_STATE_CHANGE,
                "evt-entity-2", null, first.plusSeconds(2)));

        RecomputeRequest merged = target.peek(TX);
        assertThat(merged).isNotNull();
        // EVIDENCE_EVENT outranks ENTITY_STATE_CHANGE, and the id recorded is the one that won.
        assertThat(merged.trigger()).isEqualTo(RecomputeTrigger.EVIDENCE_EVENT);
        assertThat(merged.triggerEventId()).isEqualTo("evt-evidence");
        // Earliest wins, so the max-delay ceiling measures from the start of the burst.
        assertThat(merged.requestedAt()).isEqualTo(first);
        // Context supplied by an earlier event is never erased by a later one.
        assertThat(merged.correlationId()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("different transactions debounce independently")
    void differentTransactionsAreIndependent() throws Exception {
        RecomputeDebouncer target = debouncerWith(Duration.ofMillis(120), Duration.ofSeconds(5));

        target.submit(request(RecomputeTrigger.EVIDENCE_EVENT, "a"));
        target.submit(new RecomputeRequest("TX-000000000002", MERCHANT, null,
                RecomputeTrigger.EVIDENCE_EVENT, "b", null, Instant.now()));

        assertThat(target.pendingCount()).isEqualTo(2);
        awaitExecutions(2);
        assertThat(executed).extracting(RecomputeRequest::transactionId)
                .containsExactlyInAnyOrder(TX, "TX-000000000002");
    }

    @Test
    @DisplayName("the sliding window never defers past the maximum delay")
    void continuousTrafficStillRecomputes() throws Exception {
        // Debounce 60ms but never defer more than 150ms: a steady event stream must not be able to
        // postpone the score indefinitely.
        RecomputeDebouncer target = debouncerWith(Duration.ofMillis(60), Duration.ofMillis(150));

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
        int submitted = 0;
        while (System.nanoTime() < deadline) {
            target.submit(request(RecomputeTrigger.ENTITY_STATE_CHANGE, "evt-" + submitted++));
            TimeUnit.MILLISECONDS.sleep(20);
        }

        assertThat(submitted).isGreaterThan(10);
        assertThat(executed).as("the ceiling forced at least one computation mid-stream").isNotEmpty();
        assertThat(executed.size()).as("but far fewer computations than events").isLessThan(submitted);
    }

    @Test
    @DisplayName("shutdown flushes open windows instead of dropping them")
    void closeFlushesPendingWindows() {
        RecomputeDebouncer target = debouncerWith(Duration.ofMinutes(10), Duration.ofMinutes(30));
        target.submit(request(RecomputeTrigger.EVIDENCE_EVENT, "evt-1"));
        assertThat(executed).isEmpty();

        target.close();

        assertThat(executed).hasSize(1);
        assertThat(executed.get(0).transactionId()).isEqualTo(TX);
    }

    private RecomputeRequest request(RecomputeTrigger trigger, String eventId) {
        return new RecomputeRequest(TX, MERCHANT, null, trigger, eventId, null, Instant.now());
    }

    private void awaitExecutions(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executed.size() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(executed).hasSize(expected);
    }
}
