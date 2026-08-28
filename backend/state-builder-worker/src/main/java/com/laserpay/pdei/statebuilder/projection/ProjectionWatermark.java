package com.laserpay.pdei.statebuilder.projection;

import com.laserpay.pdei.common.event.CanonicalEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The out-of-order guard. Every projection row records which event was last applied to it, and
 * this class decides whether an arriving event is allowed to move that row.
 *
 * <h2>The rule</h2>
 *
 * Given a row's watermark {@code (lastEventId, lastEventOccurredAt)} and an arriving event:
 *
 * <ol>
 *   <li><strong>No watermark</strong> (the row is new, or is a stub created to satisfy a foreign
 *       key) - <em>apply</em>.</li>
 *   <li><strong>{@code event.eventId == lastEventId}</strong> - the same event again after a
 *       redelivery or a replay. <em>Ignore</em>, idempotently.</li>
 *   <li><strong>{@code event.occurredAt < lastEventOccurredAt}</strong> - the event describes a
 *       fact older than what this row already reflects. <em>Ignore</em>: newer state must never be
 *       overwritten by an older fact.</li>
 *   <li><strong>Otherwise</strong> - <em>apply</em>. Equal {@code occurredAt} with a different
 *       {@code eventId} counts as applicable: two distinct facts can share an instant, and refusing
 *       both would lose one.</li>
 * </ol>
 *
 * <h2>Why {@code occurredAt} and not arrival order</h2>
 *
 * The Kafka partition key is {@code merchantId + ":" + aggregateId}, so events about <em>one</em>
 * aggregate are ordered. Events about <em>different</em> aggregates of the same transaction are
 * not, and a source system that was offline for six hours replays its backlog in bulk. Ordering by
 * the source's own {@code occurredAt} is the only ordering that survives both.
 *
 * <h2>Where the watermark lives</h2>
 *
 * In the row's {@code metadata} JSONB column, under {@code lastEventId} and
 * {@code lastEventOccurredAt}. The {@code transactions} and {@code disputes} tables also have
 * dedicated {@code last_event_id} columns, which the handlers keep in sync; the other projection
 * tables carry the watermark in {@code metadata} only, because
 * {@code backend/platform-persistence} owns the schema and this module does not add migrations to
 * it. See "Known gaps" in {@code context.md}.
 *
 * <h2>Consequence, stated plainly</h2>
 *
 * A stale event is dropped in full, not merged field-by-field. If {@code PaymentCaptured} is
 * processed before the {@code PaymentCreated} that carries the card metadata, that metadata is not
 * back-filled. This is the documented trade-off: correctness of the newest state is preserved
 * absolutely, at the cost of some enrichment from late-arriving older events. Readiness recomputes
 * from whatever the row holds, so the system converges rather than corrupting.
 */
public final class ProjectionWatermark {

    public static final String LAST_EVENT_ID = "lastEventId";
    public static final String LAST_EVENT_OCCURRED_AT = "lastEventOccurredAt";

    private ProjectionWatermark() {
    }

    /** Applies rule 1-4 above. */
    public static boolean shouldApply(Map<String, Object> metadata, CanonicalEvent event) {
        if (event == null) {
            return false;
        }
        if (metadata == null || metadata.isEmpty()) {
            return true;
        }
        String lastEventId = lastEventId(metadata);
        if (lastEventId != null && lastEventId.equals(event.eventId())) {
            return false;
        }
        Instant lastOccurredAt = lastOccurredAt(metadata);
        if (lastOccurredAt == null) {
            return true;
        }
        return !event.occurredAt().isBefore(lastOccurredAt);
    }

    /** True when the event is older than what the row already reflects (rule 3). */
    public static boolean isStale(Map<String, Object> metadata, CanonicalEvent event) {
        Instant lastOccurredAt = lastOccurredAt(metadata);
        return lastOccurredAt != null && event != null && event.occurredAt().isBefore(lastOccurredAt);
    }

    /** True when this exact event has already been applied to this row (rule 2). */
    public static boolean isDuplicate(Map<String, Object> metadata, CanonicalEvent event) {
        String lastEventId = lastEventId(metadata);
        return lastEventId != null && event != null && lastEventId.equals(event.eventId());
    }

    /**
     * Returns the metadata map to store on the row, with this event recorded as the watermark.
     * Never mutates the argument: entity metadata maps come back from Hibernate and may be shared
     * with the persistence context's snapshot.
     */
    public static Map<String, Object> stamp(Map<String, Object> metadata, CanonicalEvent event) {
        Map<String, Object> updated = metadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(metadata);
        if (event != null) {
            updated.put(LAST_EVENT_ID, event.eventId());
            updated.put(LAST_EVENT_OCCURRED_AT, event.occurredAt().toString());
        }
        return updated;
    }

    public static String lastEventId(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(LAST_EVENT_ID);
        return value == null ? null : String.valueOf(value);
    }

    /** The watermark instant, or {@code null} when the row carries none or an unreadable one. */
    public static Instant lastOccurredAt(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(LAST_EVENT_OCCURRED_AT);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            // A corrupt watermark must not freeze a projection: treat it as absent so the row can
            // move forward, and let the next successful write restore a readable one.
            return null;
        }
    }

    /** True when a row was created only to satisfy a foreign key and holds no real facts yet. */
    public static boolean isStub(Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(Stubs.MARKER));
    }

    /** Marker key for placeholder rows; see {@link ReferenceData}. */
    public static final class Stubs {

        public static final String MARKER = "pdeiStub";

        private Stubs() {
        }
    }
}
