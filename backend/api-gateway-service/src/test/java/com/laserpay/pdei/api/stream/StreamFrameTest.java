package com.laserpay.pdei.api.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.api.ApiTestFixtures;
import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.json.Json;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The WebSocket frame envelope of contract section 8.1, and the fold from 28 canonical event types
 * onto the seven frame types the contract allows.
 *
 * <p>The envelope is a cross-language contract: the same four field names appear in the frontend's
 * TypeScript type. A test that pins the serialised shape is the cheapest way to notice the day
 * somebody adds a fifth field here and the browser silently ignores it.</p>
 */
class StreamFrameTest {

    @Test
    @DisplayName("the serialised envelope is exactly type, at, merchantId and data")
    void envelopeShapeIsExact() throws Exception {
        StreamFrame frame = new StreamFrame(FrameType.READINESS_UPDATED, ApiTestFixtures.NOW,
                ApiTestFixtures.MERCHANT_ID, Map.of("score", 88));

        JsonNode json = Json.mapper().readTree(Json.write(frame));

        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "at", "merchantId", "data");
        assertThat(json.get("type").asText()).isEqualTo("READINESS_UPDATED");
        assertThat(json.get("at").asText()).isEqualTo("2026-08-26T10:15:30Z");
        assertThat(json.get("merchantId").asText()).isEqualTo(ApiTestFixtures.MERCHANT_ID);
        assertThat(json.get("data").get("score").asInt()).isEqualTo(88);
    }

    @Test
    @DisplayName("a HEARTBEAT with no merchant omits merchantId rather than sending null")
    void heartbeatOmitsNulls() throws Exception {
        JsonNode json = Json.mapper().readTree(Json.write(StreamFrame.heartbeat(ApiTestFixtures.NOW)));

        assertThat(json.get("type").asText()).isEqualTo("HEARTBEAT");
        assertThat(json.has("merchantId")).isFalse();
        assertThat(json.get("data").isObject()).isTrue();
    }

    @Test
    @DisplayName("all three EVIDENCE events fold onto EVIDENCE_ADDED and keep the real type in data")
    void evidenceEventsFoldButKeepTheirType() {
        for (EventType type : new EventType[]{
                EventType.EvidenceAdded, EventType.EvidenceExpired, EventType.EvidenceInvalidated}) {
            StreamFrame frame = StreamFrame.from(event(type, AggregateType.EVIDENCE,
                    ApiTestFixtures.EVIDENCE_ID));
            assertThat(frame).isNotNull();
            assertThat(frame.type()).isEqualTo(FrameType.EVIDENCE_ADDED);
            assertThat(frame.data()).containsEntry("eventType", type.name());
        }
    }

    @Test
    @DisplayName("readiness and gap events map to their own frame types")
    void readinessEventsMapDistinctly() {
        assertThat(FrameType.forEvent(EventType.ReadinessRecomputed))
                .isEqualTo(FrameType.READINESS_UPDATED);
        assertThat(FrameType.forEvent(EventType.ReadinessGapDetected))
                .isEqualTo(FrameType.GAP_DETECTED);
    }

    @Test
    @DisplayName("every CASE event maps to CASE_UPDATED")
    void caseEventsFold() {
        for (EventType type : new EventType[]{
                EventType.CaseOpened, EventType.CaseEvidenceAttached, EventType.CaseInvestigated,
                EventType.CasePrepared, EventType.CaseEscalated, EventType.CaseSubmitted,
                EventType.CaseClosed}) {
            assertThat(FrameType.forEvent(type)).isEqualTo(FrameType.CASE_UPDATED);
        }
    }

    @Test
    @DisplayName("an event the control tower does not display produces no frame at all")
    void undisplayableEventsProduceNoFrame() {
        assertThat(FrameType.forEvent(EventType.AuditRecorded)).isNull();
        assertThat(FrameType.forEvent(null)).isNull();
        assertThat(StreamFrame.from(event(EventType.AuditRecorded, AggregateType.EVIDENCE, "AUD-1")))
                .isNull();
    }

    @Test
    @DisplayName("identifiers are lifted out of the payload; the rest of the payload is not forwarded")
    void payloadIdentifiersAreLifted() {
        CanonicalEvent event = CanonicalEvent.builder()
                .eventId("22222222-2222-2222-2222-222222222222")
                .eventType(EventType.ReadinessRecomputed)
                .aggregateType(AggregateType.TRANSACTION)
                .aggregateId(ApiTestFixtures.TRANSACTION_ID)
                .merchantId(ApiTestFixtures.MERCHANT_ID)
                .occurredAt(ApiTestFixtures.NOW)
                .observedAt(ApiTestFixtures.NOW)
                .source(EventSource.INTERNAL)
                .payloadFrom(Map.of(
                        "transactionId", ApiTestFixtures.TRANSACTION_ID,
                        "score", 88,
                        "band", "NEARLY_READY",
                        "internalDebugBlob", "should not be forwarded"))
                .build();

        StreamFrame frame = StreamFrame.from(event);

        assertThat(frame).isNotNull();
        assertThat(frame.data())
                .containsEntry("transactionId", ApiTestFixtures.TRANSACTION_ID)
                .containsEntry("band", "NEARLY_READY")
                .containsKey("score")
                .doesNotContainKey("internalDebugBlob");
    }

    private static CanonicalEvent event(EventType type, AggregateType aggregateType, String aggregateId) {
        return CanonicalEvent.builder()
                .eventId("33333333-3333-3333-3333-333333333333")
                .eventType(type)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .merchantId(ApiTestFixtures.MERCHANT_ID)
                .occurredAt(ApiTestFixtures.NOW)
                .observedAt(ApiTestFixtures.NOW)
                .source(EventSource.INTERNAL)
                .build();
    }
}
