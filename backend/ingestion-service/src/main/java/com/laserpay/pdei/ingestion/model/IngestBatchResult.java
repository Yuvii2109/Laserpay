package com.laserpay.pdei.ingestion.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal result of one ingestion call: the contract response plus the ids that were assigned.
 *
 * <p>The ids are not part of the HTTP body ({@link IngestResponse} is fixed by PLATFORM-CONTRACT
 * section 8.2), but the single-event endpoint returns the first of them in the
 * {@code X-PDEI-Raw-Event-Id} response header so a caller can correlate its submission with what
 * appears on {@code pdei.raw.events.v1} without widening the contract.
 *
 * @param response  the wire response
 * @param eventIds  one id per submitted event, in submission order; null where the event was so
 *                  malformed that no id could be derived
 */
public record IngestBatchResult(IngestResponse response, List<String> eventIds) {

    public IngestBatchResult {
        // Collections.unmodifiableList, not List.copyOf: the list is null-tolerant by design and
        // List.copyOf rejects null elements.
        eventIds = eventIds == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(eventIds));
    }

    /** First assigned id, or null for an empty submission. */
    public String firstEventId() {
        return eventIds.isEmpty() ? null : eventIds.get(0);
    }
}
