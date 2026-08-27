package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.statebuilder.handler.AggregateEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Routes a canonical event to the handler that owns its aggregate.
 *
 * <p>The routing table is built once at startup from the {@link AggregateEventHandler#handles()}
 * declarations, into an {@link EnumMap} - an array lookup, not a hash, and not a chain of
 * {@code instanceof} checks. Two handlers claiming the same {@link EventType} is a startup failure:
 * both would write the same rows on the same event, and which one won would depend on bean ordering.
 *
 * <h2>Unhandled types are skipped, not dead-lettered</h2>
 *
 * {@code READINESS}, {@code CASE} and {@code AUDIT} events are internal families produced by other
 * services. If one appears on the canonical topic this worker has no projection for it, and that is
 * not an error - dead-lettering it would fill the DLQ with events that are working exactly as
 * intended. It is counted as {@code outcome="skipped"} and logged at debug.
 */
public class StateBuilderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StateBuilderDispatcher.class);

    private final Map<EventType, AggregateEventHandler> routes = new EnumMap<>(EventType.class);
    private final List<AggregateEventHandler> handlers;

    public StateBuilderDispatcher(Collection<AggregateEventHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        for (AggregateEventHandler handler : this.handlers) {
            for (EventType type : handler.handles()) {
                AggregateEventHandler previous = routes.putIfAbsent(type, handler);
                if (previous != null && previous != handler) {
                    throw new IllegalStateException("EventType " + type + " is claimed by both "
                            + previous.name() + " and " + handler.name());
                }
            }
        }
        log.info("state builder dispatcher ready: {} handlers covering {} event types ({})",
                this.handlers.size(), routes.size(), routes.keySet());
    }

    /** The handler for this event type, if any. */
    public Optional<AggregateEventHandler> handlerFor(EventType type) {
        return Optional.ofNullable(routes.get(type));
    }

    /**
     * Applies the event to its projections.
     *
     * @return {@code true} when a handler ran, {@code false} when no handler claims this type
     */
    public boolean dispatch(CanonicalEvent event) {
        AggregateEventHandler handler = routes.get(event.eventType());
        if (handler == null) {
            log.debug("no handler for {} ({}); skipping {}", event.eventType(),
                    event.aggregateType(), event.eventId());
            return false;
        }
        handler.handle(event);
        return true;
    }

    /** Event types this worker projects. Used in logs and by the readiness of its self-description. */
    public Set<EventType> handledTypes() {
        return routes.keySet();
    }

    public List<AggregateEventHandler> handlers() {
        return handlers;
    }
}
