package com.laserpay.pdei.orchestrator.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.domain.DisputeReasonCode;
import com.laserpay.pdei.common.domain.DisputeStatus;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.kafka.ConsumerGroups;
import com.laserpay.pdei.common.kafka.Topics;
import com.laserpay.pdei.common.metrics.MetricNames;
import com.laserpay.pdei.common.money.Money;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.dispute.DisputeService;
import com.laserpay.pdei.core.model.DisputeView;
import com.laserpay.pdei.orchestrator.config.KafkaConfig;
import com.laserpay.pdei.orchestrator.config.OrchestratorProperties;
import com.laserpay.pdei.orchestrator.model.CaseTimers;
import com.laserpay.pdei.orchestrator.model.DisputeCaseInput;
import com.laserpay.pdei.orchestrator.signal.CaseSignalService;
import com.laserpay.pdei.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The bridge from {@code pdei.dispute.events.v1} into Temporal.
 *
 * <ul>
 *   <li>{@code DisputeCreated} starts {@code DisputeCaseWorkflow} with workflow id
 *       {@code case-{caseId}}, where the caseId is derived from the dispute id by
 *       {@link CaseIdResolver}. A duplicate delivery therefore targets the same workflow id and is
 *       rejected by Temporal's {@code WorkflowIdReusePolicy}, which this listener treats as
 *       success.</li>
 *   <li>{@code DisputeUpdated} and {@code DisputeClosed} become {@code disputeUpdated} signals. A
 *       terminal status is what ends the step 11 follow-up loop.</li>
 *   <li>Every other event type on the topic is ignored, not failed: this consumer group also sees
 *       events it does not care about, and dead-lettering them would be noise.</li>
 * </ul>
 *
 * <p><b>Idempotency, in three independent layers</b> (contract sections 4 and 17.9):</p>
 * <ol>
 *   <li>{@code processed_events(eventId, consumerGroup)} - the Postgres claim;</li>
 *   <li>the deterministic workflow id plus the reuse policy - Temporal's own duplicate rejection;</li>
 *   <li>the workflow's signal handlers, which drop repeated evidence ids and dispute event ids.</li>
 * </ol>
 * <p>The claim is written <em>after</em> handling, deliberately. A crash between handling and
 * claiming causes a redelivery, and a redelivery is harmless; claiming first would risk marking an
 * event handled that never reached Temporal.</p>
 *
 * <p><b>Out-of-order and late events</b> are expected. The authoritative dispute state is always
 * re-read from Postgres rather than taken from the payload, so an old event carrying stale fields
 * cannot rewind a case.</p>
 */
@Component
public class DisputeEventListener {

    private static final Logger log = LoggerFactory.getLogger(DisputeEventListener.class);

    private static final String GROUP = ConsumerGroups.PDEI_CASE_ORCHESTRATOR_SERVICE;
    private static final String SERVICE = "case-orchestrator-service";

    private final CaseSignalService signals;
    private final CaseIdResolver caseIdResolver;
    private final DisputeService disputeService;
    private final ProcessedEventRepository processedEvents;
    private final OrchestratorProperties properties;
    private final CaseTimers timers;
    private final MeterRegistry meterRegistry;
    private final Clocks clock;

    public DisputeEventListener(CaseSignalService signals, CaseIdResolver caseIdResolver,
                                DisputeService disputeService,
                                ProcessedEventRepository processedEvents,
                                OrchestratorProperties properties, CaseTimers timers,
                                MeterRegistry meterRegistry, Clocks clock) {
        this.signals = signals;
        this.caseIdResolver = caseIdResolver;
        this.disputeService = disputeService;
        this.processedEvents = processedEvents;
        this.properties = properties;
        this.timers = timers;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @KafkaListener(
            topics = Topics.DISPUTE_EVENTS,
            groupId = GROUP,
            containerFactory = KafkaConfig.LISTENER_CONTAINER_FACTORY)
    public void onDisputeEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        long startNanos = System.nanoTime();
        CanonicalEvent event;
        try {
            event = Json.read(record.value(), CanonicalEvent.class);
        } catch (RuntimeException e) {
            // Unparseable payload: never retryable, straight to the DLQ via the error handler.
            // The {service,type,outcome} tag keys must match the other two call sites below:
            // Micrometer binds a meter's tag keys on first registration, so a second registration
            // of pdei_events_processed_total with a different key set is rejected outright.
            count(MetricNames.EVENTS_PROCESSED_TOTAL, MetricNames.Tag.SERVICE, SERVICE,
                    MetricNames.Tag.TYPE, "UNPARSEABLE", MetricNames.Tag.OUTCOME,
                    MetricNames.Outcome.FAILURE);
            throw e;
        }

        String eventType = event.eventType().name();
        try {
            if (processedEvents.wasProcessed(event.eventId(), GROUP)) {
                count(MetricNames.EVENTS_DUPLICATE_TOTAL, "service", SERVICE);
                log.debug("duplicate {} {} already handled by {}", eventType, event.eventId(), GROUP);
                acknowledgment.acknowledge();
                return;
            }

            boolean handled = handle(event);

            processedEvents.markProcessed(event.eventId(), GROUP);
            acknowledgment.acknowledge();
            count(MetricNames.EVENTS_PROCESSED_TOTAL, "service", SERVICE, "type", eventType,
                    "outcome", handled ? "HANDLED" : "IGNORED");
            recordLatency(eventType, startNanos);
        } catch (RuntimeException e) {
            count(MetricNames.EVENTS_PROCESSED_TOTAL, "service", SERVICE, "type", eventType,
                    "outcome", "FAILED");
            log.error("failed to handle {} {} for dispute {}: {}", eventType, event.eventId(),
                    event.aggregateId(), e.toString());
            // Rethrow: the container's error handler retries with backoff and then dead-letters.
            throw e;
        }
    }

    /** @return true when the event moved a workflow; false when it was deliberately ignored */
    private boolean handle(CanonicalEvent event) {
        EventType type = event.eventType();
        if (!type.isDisputeEvent()) {
            log.debug("ignoring non-dispute event {} on {}", type, Topics.DISPUTE_EVENTS);
            return false;
        }
        return switch (type) {
            case DisputeCreated -> onDisputeCreated(event);
            case DisputeUpdated, DisputeClosed -> onDisputeStatusChanged(event);
            default -> false;
        };
    }

    private boolean onDisputeCreated(CanonicalEvent event) {
        if (!properties.isStartWorkflowsFromEvents()) {
            log.info("workflow start disabled; ignoring DisputeCreated for {}", event.aggregateId());
            return false;
        }
        DisputeView dispute = resolveDispute(event);
        String caseId = caseIdResolver.resolve(dispute.disputeId());

        DisputeCaseInput input = DisputeCaseInput.start(
                caseId,
                dispute.disputeId(),
                dispute.merchantId(),
                dispute.transactionId(),
                dispute.reasonCode(),
                dispute.amount(),
                dispute.openedAt(),
                dispute.deadlineAt(),
                event.correlationId(),
                event.eventId(),
                properties.getDefaultActor(),
                timers);

        boolean started = signals.startCase(input);
        log.info("DisputeCreated {} -> case {} ({})", dispute.disputeId(), caseId,
                started ? "workflow started" : "workflow already existed");
        return true;
    }

    private boolean onDisputeStatusChanged(CanonicalEvent event) {
        DisputeView dispute = resolveDispute(event);
        DisputeStatus status = statusOf(event, dispute);
        String caseId = caseIdResolver.resolve(dispute.disputeId());

        boolean delivered = signals.disputeUpdated(caseId, status, event.eventId(),
                event.eventType().name(),
                event.occurredAt() == null ? clock.now() : event.occurredAt());
        if (!delivered) {
            // No running workflow: the case already closed, or none was ever opened for this dispute.
            log.info("{} for dispute {} had no running case workflow ({})", event.eventType(),
                    dispute.disputeId(), caseId);
        }
        return delivered;
    }

    /**
     * Postgres is the source of truth for a dispute; the event payload is only a fallback for the
     * window in which the row has not yet been committed by its producer.
     */
    private DisputeView resolveDispute(CanonicalEvent event) {
        Optional<DisputeView> stored = disputeService.find(event.aggregateId());
        if (stored.isPresent()) {
            return stored.get();
        }
        DisputeView fromPayload = fromPayload(event);
        if (fromPayload != null) {
            log.warn("dispute {} not in Postgres yet; using the event payload for {}",
                    event.aggregateId(), event.eventType());
            return fromPayload;
        }
        // Retryable on purpose: the producer's transaction may still be in flight.
        throw new IllegalStateException("dispute " + event.aggregateId()
                + " is not readable and event " + event.eventId() + " carries no usable payload");
    }

    private DisputeView fromPayload(CanonicalEvent event) {
        JsonNode payload = event.payload();
        if (payload == null || !payload.hasNonNull("disputeId")) {
            return null;
        }
        try {
            return Json.fromTree(payload, DisputeView.class);
        } catch (RuntimeException e) {
            log.warn("dispute event {} payload is not a DisputeView: {}", event.eventId(), e.toString());
            return fallbackDisputeView(event, payload);
        }
    }

    /**
     * Last-resort projection for a producer that shapes the payload differently. Reads only the
     * fields the workflow input genuinely needs and leaves the rest null.
     */
    private DisputeView fallbackDisputeView(CanonicalEvent event, JsonNode payload) {
        try {
            long amountMinor = payload.path("amountMinor").asLong(
                    payload.path("amount").path("amountMinor").asLong(0L));
            String currency = payload.path("currency").asText(
                    payload.path("amount").path("currency").asText(null));
            return new DisputeView(
                    payload.path("disputeId").asText(event.aggregateId()),
                    payload.path("merchantId").asText(event.merchantId()),
                    payload.path("transactionId").asText(null),
                    DisputeReasonCode.valueOf(payload.path("reasonCode").asText()),
                    DisputeStatus.valueOf(payload.path("status").asText(DisputeStatus.OPEN.name())),
                    currency == null ? null : Money.of(amountMinor, currency),
                    payload.path("networkCaseRef").asText(null),
                    payload.path("source").asText(null),
                    instantOrNull(payload, "openedAt", event.occurredAt()),
                    instantOrNull(payload, "deadlineAt", null),
                    null,
                    event.occurredAt());
        } catch (RuntimeException e) {
            log.warn("could not project dispute event {} payload: {}", event.eventId(), e.toString());
            return null;
        }
    }

    private static Instant instantOrNull(JsonNode payload, String field, Instant fallback) {
        String raw = payload.path(field).asText(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private DisputeStatus statusOf(CanonicalEvent event, DisputeView dispute) {
        String raw = event.payload() == null ? null : event.payload().path("status").asText(null);
        if (raw != null && !raw.isBlank()) {
            try {
                return DisputeStatus.valueOf(raw);
            } catch (IllegalArgumentException e) {
                log.warn("event {} carries unknown dispute status '{}'", event.eventId(), raw);
            }
        }
        if (dispute.status() != null) {
            return dispute.status();
        }
        // A DisputeClosed with nothing usable still means "stop waiting".
        return event.eventType() == EventType.DisputeClosed ? DisputeStatus.WITHDRAWN : DisputeStatus.OPEN;
    }

    private void count(String metric, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(metric, tags).increment();
        } catch (RuntimeException e) {
            // metrics never block event handling
        }
    }

    private void recordLatency(String eventType, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(MetricNames.EVENT_PROCESSING_LATENCY_SECONDS)
                    .tag("service", SERVICE)
                    .tag("type", eventType)
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // metrics never block event handling
        }
    }

}
