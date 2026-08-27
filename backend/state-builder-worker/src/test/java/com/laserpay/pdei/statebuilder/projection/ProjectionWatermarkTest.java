package com.laserpay.pdei.statebuilder.projection;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.statebuilder.Events;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The out-of-order rule, stated as tests. */
class ProjectionWatermarkTest {

    private static final Instant EARLY = Instant.parse("2026-08-26T08:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-26T09:00:00Z");

    private static CanonicalEvent event(String id, Instant occurredAt) {
        return Events.of(id, EventType.PaymentCaptured, "PAY-1", occurredAt, "{}");
    }

    @Test
    @DisplayName("rule 1: a row with no watermark accepts anything")
    void newRowAcceptsAnything() {
        assertThat(ProjectionWatermark.shouldApply(null, event("e1", EARLY))).isTrue();
        assertThat(ProjectionWatermark.shouldApply(Map.of(), event("e1", EARLY))).isTrue();
    }

    @Test
    @DisplayName("rule 2: the same event id is a duplicate and is ignored")
    void sameEventIdIsDuplicate() {
        CanonicalEvent applied = event("e1", LATE);
        Map<String, Object> metadata = ProjectionWatermark.stamp(null, applied);

        assertThat(ProjectionWatermark.shouldApply(metadata, applied)).isFalse();
        assertThat(ProjectionWatermark.isDuplicate(metadata, applied)).isTrue();
    }

    @Test
    @DisplayName("rule 3: an event older than the watermark is stale and is ignored")
    void olderEventIsStale() {
        Map<String, Object> metadata = ProjectionWatermark.stamp(null, event("e-late", LATE));
        CanonicalEvent older = event("e-early", EARLY);

        assertThat(ProjectionWatermark.shouldApply(metadata, older)).isFalse();
        assertThat(ProjectionWatermark.isStale(metadata, older)).isTrue();
    }

    @Test
    @DisplayName("rule 4: a newer event applies, and so does a different event at the same instant")
    void newerAndConcurrentEventsApply() {
        Map<String, Object> metadata = ProjectionWatermark.stamp(null, event("e-early", EARLY));

        assertThat(ProjectionWatermark.shouldApply(metadata, event("e-late", LATE))).isTrue();
        // Two distinct facts can share an instant; refusing both would lose one.
        assertThat(ProjectionWatermark.shouldApply(metadata, event("e-other", EARLY))).isTrue();
    }

    @Test
    @DisplayName("stamping never mutates the map it was given")
    void stampIsPure() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("carrier", "Delhivery");

        Map<String, Object> stamped = ProjectionWatermark.stamp(original, event("e1", LATE));

        assertThat(original).containsOnlyKeys("carrier");
        assertThat(stamped).containsKeys("carrier", ProjectionWatermark.LAST_EVENT_ID,
                ProjectionWatermark.LAST_EVENT_OCCURRED_AT);
        assertThat(stamped.get(ProjectionWatermark.LAST_EVENT_ID)).isEqualTo("e1");
        assertThat(stamped.get(ProjectionWatermark.LAST_EVENT_OCCURRED_AT))
                .isEqualTo(LATE.toString());
    }

    @Test
    @DisplayName("the watermark round-trips through the JSONB string form")
    void watermarkRoundTrips() {
        Map<String, Object> metadata = ProjectionWatermark.stamp(null, event("e1", LATE));

        assertThat(ProjectionWatermark.lastEventId(metadata)).isEqualTo("e1");
        assertThat(ProjectionWatermark.lastOccurredAt(metadata)).isEqualTo(LATE);
    }

    @Test
    @DisplayName("a corrupt watermark does not freeze the row: it is treated as absent")
    void corruptWatermarkIsIgnored() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ProjectionWatermark.LAST_EVENT_OCCURRED_AT, "not-a-timestamp");

        assertThat(ProjectionWatermark.lastOccurredAt(metadata)).isNull();
        assertThat(ProjectionWatermark.shouldApply(metadata, event("e1", EARLY))).isTrue();
    }

    @Test
    @DisplayName("a stub row is marked as such and still accepts the real event")
    void stubRowsAcceptTheRealEvent() {
        Map<String, Object> metadata = ReferenceData.stubMetadata("implied by ShipmentDelivered");

        assertThat(ProjectionWatermark.isStub(metadata)).isTrue();
        assertThat(ProjectionWatermark.shouldApply(metadata, event("e1", EARLY))).isTrue();
    }
}
