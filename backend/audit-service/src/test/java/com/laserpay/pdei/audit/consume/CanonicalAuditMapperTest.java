package com.laserpay.pdei.audit.consume;

import com.laserpay.pdei.common.event.AggregateType;
import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.event.AuditEvent;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.id.IdPrefix;
import com.laserpay.pdei.common.id.Ids;
import com.laserpay.pdei.common.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deriving an audit entry from a domain event.
 *
 * <p>Two properties carry the risk and both are asserted here. The mapping must produce something
 * the database will accept for <em>every</em> event type in the enum - a mapping that fails on one
 * event type would leave a hole in the trail exactly where an auditor looks. And the derived audit
 * id must be a pure function of the event id, or a topic replay six months from now would append a
 * second chain link for every fact already recorded.
 */
class CanonicalAuditMapperTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-26T10:15:30.123Z");
    private static final Instant OBSERVED = Instant.parse("2026-08-26T10:15:31.004Z");

    @Test
    @DisplayName("a canonical event maps onto the audit entry the schema expects")
    void mapsTheEnvelopeOntoAnAuditEntry() {
        CanonicalEvent event = event(EventType.ShipmentDelivered, AggregateType.SHIPMENT,
                "SHP-00000001", EventSource.LOGISTICS);

        AuditEvent audit = CanonicalAuditMapper.toAuditEvent(event);

        assertThat(Ids.hasPrefix(audit.auditId(), IdPrefix.AUDIT)).isTrue();
        assertThat(audit.entityType()).isEqualTo("SHIPMENT");
        assertThat(audit.entityId()).isEqualTo("SHP-00000001");
        assertThat(audit.merchantId()).isEqualTo("MER-00000001");
        assertThat(audit.action()).isEqualTo("ShipmentDelivered");
        assertThat(audit.actor()).isEqualTo("LOGISTICS");
        assertThat(audit.actorType()).isEqualTo(ActorType.SYSTEM);
        // The fact's own time, not the time PDEI happened to see it.
        assertThat(audit.occurredAt()).isEqualTo(OCCURRED);
        assertThat(audit.correlationId()).isEqualTo("corr-1");
        // A canonical event carries no prior state, so there is nothing honest to put in `before`.
        assertThat(audit.before()).isNull();
        assertThat(audit.verifyHash()).isTrue();
    }

    @Test
    @DisplayName("the after state carries the envelope an auditor needs to trace the record back")
    void afterStateCarriesProvenance() {
        CanonicalEvent event = event(EventType.EvidenceAdded, AggregateType.EVIDENCE,
                "EV-00000001", EventSource.MERCHANT_PORTAL);

        AuditEvent audit = CanonicalAuditMapper.toAuditEvent(event);

        assertThat(audit.after()).isNotNull();
        assertThat(audit.after().get("eventId").asText()).isEqualTo(event.eventId());
        assertThat(audit.after().get("eventType").asText()).isEqualTo("EvidenceAdded");
        assertThat(audit.after().get("observedAt").asText()).isEqualTo(OBSERVED.toString());
        assertThat(audit.after().get("payload").get("evidenceId").asText()).isEqualTo("EV-00000001");
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    @DisplayName("every event type maps to an entity type the check constraint accepts")
    void everyEventTypeProducesAStorableEntry(EventType type) {
        CanonicalEvent event = event(type, type.aggregateType(), "AGG-00000001", EventSource.INTERNAL);

        AuditEvent audit = CanonicalAuditMapper.toAuditEvent(event);

        // ck_audit_events_entity_type in V8__audit.sql accepts exactly the AggregateType names.
        assertThat(AggregateType.valueOf(audit.entityType())).isNotNull();
        assertThat(Ids.hasPrefix(audit.auditId(), IdPrefix.AUDIT)).isTrue();
        assertThat(audit.auditId().length()).isLessThanOrEqualTo(64);
        assertThat(audit.verifyHash()).isTrue();
    }

    @Test
    @DisplayName("the derived audit id is a pure function of the event id, so replay is idempotent")
    void auditIdIsDeterministic() {
        String eventId = "3f6a5f1e-0000-4000-8000-000000000001";

        assertThat(CanonicalAuditMapper.auditIdFor(eventId))
                .isEqualTo(CanonicalAuditMapper.auditIdFor(eventId));
        assertThat(CanonicalAuditMapper.auditIdFor(eventId))
                .isNotEqualTo(CanonicalAuditMapper.auditIdFor("3f6a5f1e-0000-4000-8000-000000000002"));
    }

    @Test
    @DisplayName("a merchant portal action is attributed to a merchant user, not to the platform")
    void actorTypeFollowsTheSource() {
        AuditEvent portal = CanonicalAuditMapper.toAuditEvent(
                event(EventType.EvidenceAdded, AggregateType.EVIDENCE, "EV-1", EventSource.MERCHANT_PORTAL));
        AuditEvent simulator = CanonicalAuditMapper.toAuditEvent(
                event(EventType.PaymentCaptured, AggregateType.PAYMENT, "PAY-1", EventSource.SIMULATOR));
        AuditEvent psp = CanonicalAuditMapper.toAuditEvent(
                event(EventType.PaymentCaptured, AggregateType.PAYMENT, "PAY-2", EventSource.PSP_ADAPTER));

        assertThat(portal.actorType()).isEqualTo(ActorType.MERCHANT_USER);
        assertThat(simulator.actorType()).isEqualTo(ActorType.SIMULATOR);
        // PDEI does not know which human was behind a PSP webhook, and does not pretend to.
        assertThat(psp.actorType()).isEqualTo(ActorType.SYSTEM);
    }

    private static CanonicalEvent event(EventType type, AggregateType aggregateType,
                                        String aggregateId, EventSource source) {
        return CanonicalEvent.builder()
                .eventId("evt-" + type.name() + "-" + aggregateId)
                .eventType(type)
                .schemaVersion(1)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .merchantId("MER-00000001")
                .correlationId("corr-1")
                .occurredAt(OCCURRED)
                .observedAt(OBSERVED)
                .source(source)
                .idempotencyKey("idem-" + aggregateId)
                .payload(Json.tree(Map.of("evidenceId", aggregateId, "amountMinor", 1299900L,
                        "currency", "INR")))
                .build();
    }
}
