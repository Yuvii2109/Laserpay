package com.laserpay.pdei.readiness.sweep;

import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.core.spi.EventPublisherPort;

import java.util.ArrayList;
import java.util.List;

/** {@link EventPublisherPort} that records instead of producing, for assertions about what was emitted. */
final class RecordingEventPublisher implements EventPublisherPort {

    private final List<Published> events = new ArrayList<>();
    private final List<AuditEvent> auditEvents = new ArrayList<>();

    @Override
    public void publish(String topic, CanonicalEvent event) {
        events.add(new Published(topic, event));
    }

    @Override
    public void publishAudit(AuditEvent event) {
        auditEvents.add(event);
    }

    List<Published> events() {
        return List.copyOf(events);
    }

    List<AuditEvent> auditEvents() {
        return List.copyOf(auditEvents);
    }

    List<CanonicalEvent> ofType(EventType type) {
        return events.stream()
                .map(Published::event)
                .filter(event -> event != null && event.eventType() == type)
                .toList();
    }

    record Published(String topic, CanonicalEvent event) {
    }
}
