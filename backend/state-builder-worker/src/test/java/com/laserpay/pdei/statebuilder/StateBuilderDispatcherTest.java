package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.statebuilder.handler.AggregateEventHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateBuilderDispatcherTest {

    private static final Instant AT = Instant.parse("2026-08-26T09:00:00Z");

    @Test
    @DisplayName("routes each event type to the handler that claims it")
    void routesByEventType() {
        Recorder payments = new Recorder(EnumSet.of(EventType.PaymentCaptured));
        Recorder shipments = new Recorder(EnumSet.of(EventType.ShipmentDelivered));
        StateBuilderDispatcher dispatcher = new StateBuilderDispatcher(List.of(payments, shipments));

        assertThat(dispatcher.dispatch(event(EventType.PaymentCaptured, "PAY-1"))).isTrue();
        assertThat(dispatcher.dispatch(event(EventType.ShipmentDelivered, "SHP-1"))).isTrue();

        assertThat(payments.seen).hasSize(1);
        assertThat(shipments.seen).hasSize(1);
        assertThat(dispatcher.handledTypes()).containsExactlyInAnyOrder(
                EventType.PaymentCaptured, EventType.ShipmentDelivered);
    }

    @Test
    @DisplayName("an unclaimed event type is skipped, and the caller is told so")
    void skipsUnclaimedTypes() {
        StateBuilderDispatcher dispatcher = new StateBuilderDispatcher(
                List.of(new Recorder(EnumSet.of(EventType.PaymentCaptured))));

        assertThat(dispatcher.dispatch(event(EventType.CaseOpened, "CASE-1"))).isFalse();
        assertThat(dispatcher.handlerFor(EventType.CaseOpened)).isEmpty();
    }

    @Test
    @DisplayName("two handlers claiming one event type is a startup failure, not a coin toss")
    void rejectsOverlappingHandlers() {
        assertThatThrownBy(() -> new StateBuilderDispatcher(List.of(
                new Recorder(EnumSet.of(EventType.PaymentCaptured)),
                new Recorder(EnumSet.of(EventType.PaymentCaptured)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PaymentCaptured");
    }

    private static CanonicalEvent event(EventType type, String aggregateId) {
        return Events.of(type, aggregateId, AT, "{}");
    }

    private static final class Recorder implements AggregateEventHandler {

        private final Set<EventType> types;
        private final List<CanonicalEvent> seen = new ArrayList<>();

        private Recorder(Set<EventType> types) {
            this.types = types;
        }

        @Override
        public Set<EventType> handles() {
            return types;
        }

        @Override
        public void handle(CanonicalEvent event) {
            seen.add(event);
        }
    }
}
