package com.laserpay.pdei.ingestion.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/**
 * One submitted raw event, as accepted by {@code POST /ingest/v1/events} and
 * {@code POST /ingest/v1/events/batch}.
 *
 * <p>Wire shape is {@code schemas/events/raw-event.schema.json}, and this record is intentionally
 * one-to-one with {@link com.laserpay.pdei.common.event.RawEventEnvelope} apart from the three
 * routing hints ({@code aggregateId}, {@code correlationId}, {@code causationId}) that ingestion
 * folds into the envelope's {@code headers} map, and {@code occurredAt}, which is the source-side
 * time and belongs to the fact rather than to the delivery.
 *
 * <p>Every field except {@code sourceSystem}, {@code sourceEventType}, {@code merchantId} and the
 * body is optional; the defaults are derived in
 * {@code com.laserpay.pdei.ingestion.service.IngestionService}.
 *
 * <p>Aliases exist because adapters are written by other people: {@code eventId} is accepted for
 * {@code rawEventId} and {@code payload} for {@code body}. Unknown properties are ignored rather
 * than rejected - a newer adapter must never be broken by an older ingestion build.
 *
 * @param rawEventId      submitter-assigned id for this delivery; generated when absent
 * @param sourceSystem    adapter identity, e.g. {@code psp-adapter}
 * @param sourceEventType source vocabulary, e.g. {@code payment_intent.succeeded}. Not an
 *                        {@code EventType} - normalization-worker owns that mapping
 * @param merchantId      owning merchant, {@code MER-…}
 * @param aggregateId     optional aggregate this fact is about; drives the Kafka partition key
 * @param correlationId   optional caller-supplied correlation id, generated when absent
 * @param causationId     optional id of the fact that caused this one
 * @param occurredAt      when the fact happened in the source system
 * @param idempotencyKey  stable per-fact key; the {@code Idempotency-Key} HTTP header wins over it
 * @param headers         source transport headers worth preserving
 * @param body            the source payload, kept verbatim
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestRequest(@JsonAlias({"eventId", "event_id"}) String rawEventId,
                            @JsonAlias({"source_system", "source"}) String sourceSystem,
                            @JsonAlias({"source_event_type", "eventType", "type"}) String sourceEventType,
                            @JsonAlias({"merchant_id"}) String merchantId,
                            @JsonAlias({"aggregate_id"}) String aggregateId,
                            @JsonAlias({"correlation_id"}) String correlationId,
                            @JsonAlias({"causation_id"}) String causationId,
                            @JsonAlias({"occurred_at"}) Instant occurredAt,
                            @JsonAlias({"idempotency_key"}) String idempotencyKey,
                            Map<String, String> headers,
                            @JsonAlias({"payload", "data"}) JsonNode body) {

    /** Convenience for tests and the webhook adapter path. */
    public static IngestRequest of(String sourceSystem,
                                   String sourceEventType,
                                   String merchantId,
                                   JsonNode body) {
        return new IngestRequest(null, sourceSystem, sourceEventType, merchantId,
                null, null, null, null, null, null, body);
    }

    /** Returns a copy with the idempotency key replaced (used to honour the HTTP header). */
    public IngestRequest withIdempotencyKey(String key) {
        return new IngestRequest(rawEventId, sourceSystem, sourceEventType, merchantId, aggregateId,
                correlationId, causationId, occurredAt, key, headers, body);
    }
}
