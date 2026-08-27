package com.laserpay.pdei.api.dto;

import com.laserpay.pdei.core.model.TimelineEntry;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /transactions/{transactionId}/timeline} and {@code GET /ai-tools/timeline/{id}}.
 *
 * <p>Entries come straight from {@code TimelineService} and are already sorted by when things
 * actually happened rather than by when the platform observed them, which is the distinction that
 * makes a late-arriving delivery event read correctly on the page.</p>
 */
public record TimelineResponse(
        String transactionId,
        List<TimelineEntry> entries,
        int count,
        Instant generatedAt) {

    public TimelineResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static TimelineResponse of(String transactionId, List<TimelineEntry> entries, Instant at) {
        List<TimelineEntry> safe = entries == null ? List.of() : List.copyOf(entries);
        return new TimelineResponse(transactionId, safe, safe.size(), at);
    }
}
