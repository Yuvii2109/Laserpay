package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The gateway's only Kafka consumer: turns platform events into control-tower frames.
 *
 * <p>Subscribes to the four topics the control tower needs:</p>
 *
 * <ul>
 *   <li>{@code pdei.readiness.events.v1} and {@code pdei.case.events.v1}, where contract section 4
 *       names api-gateway-service as a consumer;</li>
 *   <li>{@code pdei.evidence.events.v1} and {@code pdei.dispute.events.v1}, because EVIDENCE_ADDED
 *       and DISPUTE_CREATED are declared frame types in section 8.1 and there is nowhere else for
 *       them to come from.</li>
 * </ul>
 *
 * <h2>Idempotent, late and out of order</h2>
 * <p>Every record is deduplicated on {@code eventId} before it is fanned out, so a rebalance replay
 * or a producer retry redraws nothing. Ordering is not enforced and does not need to be: a frame
 * carries identifiers, not state, and the browser refetches the current value over REST. An
 * out-of-order or late frame therefore causes one extra fetch, never a stale render.</p>
 *
 * <h2>Never fails a record</h2>
 * <p>An exception escaping a listener triggers redelivery, and redelivering a record nobody could
 * parse would loop forever. A malformed or undisplayable record is counted, logged and acknowledged:
 * the consequence is a dashboard that misses one update, which the next event or the operator's own
 * refresh corrects. Dead-lettering belongs to the consumer that owns the topic's state, not to a
 * UI feed.</p>
 *
 * <p>Disabled by {@code pdei.api.stream.kafka-enabled=false}, which is how an environment without a
 * broker runs without a consumer.</p>
 */
@Component
@ConditionalOnProperty(prefix = "pdei.api.stream", name = "kafka-enabled",
        havingValue = "true", matchIfMissing = true)
public class ControlTowerEventListener {

    private static final Logger log = LoggerFactory.getLogger(ControlTowerEventListener.class);

    private static final String SERVICE = "api-gateway-service";
    private static final String UNKNOWN_TYPE = "unknown";

    private final StreamHub hub;
    private final StreamEventDeduplicator deduplicator;
    private final MeterRegistry meterRegistry;

    public ControlTowerEventListener(StreamHub hub, StreamEventDeduplicator deduplicator,
                                     MeterRegistry meterRegistry) {
        this.hub = hub;
        this.deduplicator = deduplicator;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            id = "pdei-control-tower",
            groupId = ConsumerGroups.PDEI_API_GATEWAY_SERVICE,
            topics = {
                    "#{T(com.laserpay.pdei.common.kafka.Topics).READINESS_EVENTS}",
                    "#{T(com.laserpay.pdei.common.kafka.Topics).CASE_EVENTS}",
                    "#{T(com.laserpay.pdei.common.kafka.Topics).EVIDENCE_EVENTS}",
                    "#{T(com.laserpay.pdei.common.kafka.Topics).DISPUTE_EVENTS}"
            })
    public void onEvent(ConsumerRecord<String, String> record) {
        CanonicalEvent event;
        try {
            event = Json.mapper().readValue(record.value(), CanonicalEvent.class);
        } catch (Exception e) {
            processed(UNKNOWN_TYPE, MetricNames.Outcome.FAILURE);
            log.warn("Unreadable record on {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), e.toString());
            return;
        }

        String type = event.eventType() == null ? UNKNOWN_TYPE : event.eventType().name();

        if (!deduplicator.firstTime(event.eventId())) {
            meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_TOTAL,
                    MetricNames.Tag.SERVICE, SERVICE).increment();
            processed(type, MetricNames.Outcome.DUPLICATE);
            return;
        }

        StreamFrame frame = StreamFrame.from(event);
        if (frame == null) {
            // A type the control tower does not display, for example AuditRecorded on a shared topic.
            processed(type, MetricNames.Outcome.SKIPPED);
            return;
        }

        try {
            int delivered = hub.broadcast(frame);
            processed(type, MetricNames.Outcome.SUCCESS);
            if (log.isTraceEnabled()) {
                log.trace("Fanned out {} for merchant {} to {} subscriber(s)",
                        frame.type(), frame.merchantId(), delivered);
            }
        } catch (RuntimeException e) {
            processed(type, MetricNames.Outcome.FAILURE);
            log.warn("Fan-out failed for event {}: {}", event.eventId(), e.toString());
        }
    }

    /**
     * {@code pdei_events_processed_total{service,type,outcome}} (contract section 13).
     *
     * <p>All three tags are always supplied. Micrometer binds a meter's tag keys on first
     * registration, so a counter incremented once with two tags and once with three would either be
     * rejected or split into two series with the same name.</p>
     */
    private void processed(String type, String outcome) {
        Counter.builder(MetricNames.EVENTS_PROCESSED_TOTAL)
                .tag(MetricNames.Tag.SERVICE, SERVICE)
                .tag(MetricNames.Tag.TYPE, type)
                .tag(MetricNames.Tag.OUTCOME, outcome)
                .register(meterRegistry)
                .increment();
    }
}
