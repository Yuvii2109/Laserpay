package com.laserpay.pdei.ingestion.model;

import java.time.Instant;

/**
 * What {@code GET /ingest/v1/stats} returns: "accepted/rejected/deduped counters"
 * (PLATFORM-CONTRACT section 8.2).
 *
 * <p>These are <em>process-lifetime</em> counters held in memory, not a durable ledger. They exist
 * for a quick human sanity check and for the demo console; the authoritative time series is
 * Prometheus scraping {@code /actuator/prometheus}
 * ({@code pdei_events_ingested_total}, {@code pdei_events_duplicate_total},
 * {@code pdei_events_processed_total}). A restart resets them, which is why {@code since} is
 * reported alongside.
 *
 * @param accepted          events published to {@code pdei.raw.events.v1}
 * @param rejected          events that failed validation or publication
 * @param duplicates        events suppressed by idempotency
 * @param deadLettered      events whose publication failure produced a {@code pdei.dlq.v1} record
 * @param registeredSchemas number of schemas currently loaded
 * @param since             when this process started counting
 * @param at                when this snapshot was taken
 */
public record IngestionStats(long accepted,
                             long rejected,
                             long duplicates,
                             long deadLettered,
                             int registeredSchemas,
                             Instant since,
                             Instant at) {
}
