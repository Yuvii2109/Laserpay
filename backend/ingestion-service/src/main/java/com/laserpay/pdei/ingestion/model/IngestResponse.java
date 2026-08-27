package com.laserpay.pdei.ingestion.model;

import java.util.List;

/**
 * The ingestion response body, exactly as PLATFORM-CONTRACT section 8.2 specifies:
 * {@code { "accepted": n, "rejected": [...], "duplicates": n }}. Returned with
 * {@code 202 Accepted} for every submission that was structurally readable, including one where
 * every event was rejected - the request itself succeeded, the events did not.
 *
 * <p>Top-level fields are deliberately limited to those three. The id assigned to a single accepted
 * event is returned in the {@code X-PDEI-Raw-Event-Id} response header instead of widening this
 * contract.
 *
 * <p>{@code accepted + duplicates + rejected.size()} always equals the number of submitted events.
 *
 * @param accepted   events validated, claimed and published to {@code pdei.raw.events.v1}
 * @param rejected   events that failed validation or publication, with per-field detail
 * @param duplicates events suppressed because their idempotency key had already been claimed
 */
public record IngestResponse(int accepted, List<RejectedEvent> rejected, int duplicates) {

    public IngestResponse {
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    public static IngestResponse empty() {
        return new IngestResponse(0, List.of(), 0);
    }

    /** Total events accounted for; invariant check for tests and the stats endpoint. */
    public int total() {
        return accepted + duplicates + rejected.size();
    }
}
