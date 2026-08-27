package com.laserpay.pdei.core.model;

import com.laserpay.pdei.common.event.AggregateType;

import java.time.Instant;
import java.util.Map;

/**
 * One row of the unified event + evidence timeline.
 *
 * <p>{@code at}, {@code eventType} and {@code summary} are the fields serialised into
 * {@link InvestigationContext#timeline()} (platform contract 9.1).</p>
 */
public record TimelineEntry(
        String entryId,
        Instant at,
        String eventType,
        AggregateType aggregateType,
        String aggregateId,
        String summary,
        String source,
        Map<String, Object> details) {

    public TimelineEntry {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static TimelineEntry of(Instant at, String eventType, AggregateType aggregateType,
                                   String aggregateId, String summary, String source) {
        return new TimelineEntry(aggregateId + "@" + eventType, at, eventType, aggregateType,
                aggregateId, summary, source, Map.of());
    }
}
