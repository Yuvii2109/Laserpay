package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import com.laserpay.pdei.statebuilder.handler.AggregateEventHandler;
import com.laserpay.pdei.statebuilder.support.IdempotencyGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The transactional unit of work: duplicate suppression, dispatch, and the metrics that make the
 * event funnel measurable.
 */
class StateBuilderServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-26T09:00:00Z");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final Set<String> claimed = new HashSet<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RecordingHandler recorder = new RecordingHandler();

    private StateBuilderService service;

    @BeforeEach
    void setUp() {
        claimed.clear();
        when(processedEvents.markProcessed(anyString(), anyString()))
                .thenAnswer(invocation -> claimed.add(
                        invocation.getArgument(0) + "@" + invocation.getArgument(1)));

        IdempotencyGuard guard = new IdempotencyGuard(processedEvents, null,
                ConsumerGroups.PDEI_STATE_BUILDER_WORKER, null);
        service = new StateBuilderService(new StateBuilderDispatcher(List.of(recorder)), guard,
                meterRegistry);
    }

    @Test
    @DisplayName("an event is dispatched to its handler and counted as a success")
    void dispatchesAndCounts() {
        assertThat(service.handle(capture("e1"))).isTrue();

        assertThat(recorder.handled).hasSize(1);
        assertThat(counter(MetricNames.Outcome.SUCCESS)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("duplicate delivery: the second delivery is suppressed before reaching the handler")
    void suppressesDuplicateDelivery() {
        CanonicalEvent event = capture("e-dup");

        assertThat(service.handle(event)).isTrue();
        assertThat(service.handle(event)).isFalse();

        assertThat(recorder.handled).hasSize(1);
        assertThat(counter(MetricNames.Outcome.DUPLICATE)).isEqualTo(1.0d);
        assertThat(meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL,
                MetricNames.Tag.SERVICE, "state-builder-worker").count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an event type this worker does not project is skipped, never dead-lettered")
    void skipsUnhandledTypes() {
        CanonicalEvent readiness = Events.of("e-readiness", EventType.ReadinessRecomputed,
                Events.TRANSACTION_ID, OCCURRED_AT, "{}");

        assertThat(service.handle(readiness)).isFalse();
        assertThat(recorder.handled).isEmpty();
        assertThat(meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, "state-builder-worker",
                MetricNames.Tag.TYPE, EventType.ReadinessRecomputed.name(),
                MetricNames.Tag.OUTCOME, MetricNames.Outcome.SKIPPED).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a handler failure propagates so the transaction rolls back and the event retries")
    void propagatesHandlerFailure() {
        recorder.failWith = new IllegalStateException("database unavailable");

        assertThatThrownBy(() -> service.handle(capture("e-fail")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
    }

    // --- helpers ------------------------------------------------------------------------------

    private double counter(String outcome) {
        return meterRegistry.counter(MetricNames.EVENTS_PROCESSED_TOTAL,
                MetricNames.Tag.SERVICE, "state-builder-worker",
                MetricNames.Tag.TYPE, EventType.PaymentCaptured.name(),
                MetricNames.Tag.OUTCOME, outcome).count();
    }

    private static CanonicalEvent capture(String eventId) {
        return Events.of(eventId, EventType.PaymentCaptured, "PAY-1", OCCURRED_AT,
                "{ \"paymentId\": \"PAY-1\", \"transactionId\": \"TX-82918\" }");
    }

    /** Records what it was given; optionally fails, to exercise the rollback path. */
    private static final class RecordingHandler implements AggregateEventHandler {

        private final List<CanonicalEvent> handled = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public Set<EventType> handles() {
            return EnumSet.of(EventType.PaymentCaptured);
        }

        @Override
        public void handle(CanonicalEvent event) {
            if (failWith != null) {
                throw failWith;
            }
            handled.add(event);
        }
    }
}
