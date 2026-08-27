package com.laserpay.pdei.readiness.consume;

import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.readiness.recompute.RecomputeTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which events may cause a recomputation, and how each is classified.
 *
 * <p>The important assertion here is the negative one: this worker publishes READINESS events, and
 * if it also consumed them it would recompute forever. The filter is the thing that prevents that,
 * so it is tested exhaustively over the enum rather than by example - a new event type added to
 * {@code EventType} cannot slip past it unnoticed.
 */
class EventRelevanceTest {

    @ParameterizedTest
    @EnumSource(EventType.class)
    @DisplayName("every event type is either relevant to readiness or explicitly downstream of it")
    void everyEventTypeIsClassified(EventType type) {
        boolean downstream = type.isReadinessEvent() || type.isCaseEvent() || type.isAuditEvent();
        assertThat(EventIntake.isRelevant(type)).isEqualTo(!downstream);
    }

    @Test
    @DisplayName("this worker never reacts to its own output")
    void readinessEventsAreIgnored() {
        assertThat(EventIntake.isRelevant(EventType.ReadinessRecomputed)).isFalse();
        assertThat(EventIntake.isRelevant(EventType.ReadinessGapDetected)).isFalse();
    }

    @Test
    @DisplayName("a null event type is never relevant")
    void nullIsNotRelevant() {
        assertThat(EventIntake.isRelevant(null)).isFalse();
    }

    @Test
    @DisplayName("evidence events outrank entity state changes when a burst is merged")
    void triggerClassification() {
        assertThat(RecomputeTrigger.forEvent(EventType.EvidenceAdded))
                .isEqualTo(RecomputeTrigger.EVIDENCE_EVENT);
        assertThat(RecomputeTrigger.forEvent(EventType.EvidenceExpired))
                .isEqualTo(RecomputeTrigger.EVIDENCE_EVENT);
        assertThat(RecomputeTrigger.forEvent(EventType.DisputeCreated))
                .isEqualTo(RecomputeTrigger.DISPUTE_EVENT);
        assertThat(RecomputeTrigger.forEvent(EventType.ShipmentDelivered))
                .isEqualTo(RecomputeTrigger.ENTITY_STATE_CHANGE);

        assertThat(RecomputeTrigger.ENTITY_STATE_CHANGE.merge(RecomputeTrigger.EVIDENCE_EVENT))
                .isEqualTo(RecomputeTrigger.EVIDENCE_EVENT);
        assertThat(RecomputeTrigger.DISPUTE_EVENT.merge(RecomputeTrigger.EVIDENCE_EVENT))
                .isEqualTo(RecomputeTrigger.DISPUTE_EVENT);
        assertThat(RecomputeTrigger.EVIDENCE_EVENT.merge(null))
                .isEqualTo(RecomputeTrigger.EVIDENCE_EVENT);
    }

    @Test
    @DisplayName("every trigger name is one the readiness_snapshots check constraint accepts")
    void triggerNamesMatchTheMigration() {
        // ck_readiness_snapshots_trigger in V6__readiness.sql
        assertThat(RecomputeTrigger.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("EVIDENCE_EVENT", "ENTITY_STATE_CHANGE",
                        "POLICY_VERSION_CHANGE", "NIGHTLY_SWEEP", "MANUAL_RECOMPUTE", "DISPUTE_EVENT");
    }
}
