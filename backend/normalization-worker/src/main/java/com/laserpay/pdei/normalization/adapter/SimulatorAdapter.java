package com.laserpay.pdei.normalization.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.normalization.support.Payloads;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Events from simulator-service, whose source vocabulary is already the canonical one.
 *
 * <p>The simulator deliberately publishes to {@code pdei.raw.events.v1} rather than injecting
 * canonical events directly: simulated load must traverse exactly the same code path as production
 * traffic, including normalization, idempotency and dead-lettering. A benchmark that skipped a hop
 * would be measuring a system nobody runs.
 *
 * <p>Consequently this adapter is thin - it validates that the declared type exists in this build,
 * derives the aggregate id, and preserves the simulator's {@code occurredAt} verbatim. Preserving
 * it is what makes the {@code DELAYED_EVENT} and {@code OUT_OF_ORDER_EVENT} chaos types meaningful:
 * the simulator backdates {@code occurredAt}, normalization stamps a fresh {@code observedAt}, and
 * the lateness is visible all the way to the timeline.
 *
 * <p>Internal event families (CASE, READINESS, AUDIT) are deliberately <em>not</em> accepted here.
 * PDEI produces those itself; letting an external producer inject them would let a simulated run
 * fabricate case history.
 */
public class SimulatorAdapter extends AbstractSourceAdapter {

    private static final Map<String, EventType> MAPPINGS = Arrays.stream(EventType.values())
            .filter(type -> !type.isInternal())
            .collect(Collectors.toMap(EventType::name, Function.identity(), (a, b) -> a,
                    LinkedHashMap::new));

    private static final Set<String> ALIASES = Set.of("SIMULATOR", "SIM", "simulator-service");

    public SimulatorAdapter(String defaultCurrency) {
        super("SIMULATOR", ALIASES, MAPPINGS, defaultCurrency);
    }

    @Override
    public EventSource eventSource() {
        return EventSource.SIMULATOR;
    }

    @Override
    protected CanonicalEvent map(RawEventEnvelope raw, EventType eventType, Instant observedAt) {
        JsonNode wrapped = Payloads.first(raw.body(), "payload", "data");
        JsonNode payload = wrapped != null && wrapped.isObject() ? wrapped : raw.body();

        String aggregateId = CanonicalIds.forEvent(eventType, payload);
        if (aggregateId == null) {
            throw new UnmappableEventException(raw.sourceSystem(), raw.sourceEventType(),
                    "simulated " + eventType + " payload carries no "
                            + eventType.aggregateType() + " identifier");
        }

        Instant occurredAt = CanonicalIds.occurredAt(eventType, payload);
        if (occurredAt == null) {
            occurredAt = Payloads.instantOr(raw.body(), raw.receivedAt(), "occurredAt", "occurred_at");
        }

        return envelope(raw, eventType, aggregateId, occurredAt, observedAt, payload.deepCopy());
    }
}
