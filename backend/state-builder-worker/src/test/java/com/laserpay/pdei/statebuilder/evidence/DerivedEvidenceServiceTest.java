package com.laserpay.pdei.statebuilder.evidence;

import com.laserpay.pdei.common.domain.EvidenceSource;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.core.evidence.CreateEvidenceCommand;
import com.laserpay.pdei.statebuilder.EvidenceStubs;
import com.laserpay.pdei.statebuilder.Events;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedEvidenceServiceTest {

    private static final Instant DELIVERED_AT = Instant.parse("2026-08-27T11:02:00Z");

    private static CanonicalEvent delivery() {
        return Events.of(EventType.ShipmentDelivered, "SHP-771", DELIVERED_AT, """
                { "shipmentId": "SHP-771", "transactionId": "TX-82918",
                  "signedBy": "R. Sharma", "deliveredAt": "2026-08-27T11:02:00Z" }
                """);
    }

    @Test
    @DisplayName("the derived document is a pure function of the event, so replays deduplicate")
    void documentIsDeterministic() {
        CanonicalEvent first = delivery();
        // Same fact, observed later: the observedAt difference must not change the content hash.
        CanonicalEvent replayed = first.toBuilder()
                .observedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build();

        byte[] a = DerivedEvidenceService.documentFor(first, EvidenceType.DELIVERY_PROOF,
                "TX-82918", "SHP-771", "Shipment SHP-771 delivered");
        byte[] b = DerivedEvidenceService.documentFor(replayed, EvidenceType.DELIVERY_PROOF,
                "TX-82918", "SHP-771", "Shipment SHP-771 delivered");

        assertThat(a).isEqualTo(b);
        String json = new String(a, StandardCharsets.UTF_8);
        assertThat(json).contains("\"derivedFromEventId\"").contains("\"occurredAt\"");
        assertThat(json).doesNotContain("observedAt");
    }

    @Test
    @DisplayName("event provenance maps onto evidence provenance without losing the distinction")
    void mapsProvenance() {
        assertThat(DerivedEvidenceService.sourceOf(
                Events.withSource(delivery(), EventSource.LOGISTICS)))
                .isEqualTo(EvidenceSource.LOGISTICS);
        assertThat(DerivedEvidenceService.sourceOf(
                Events.withSource(delivery(), EventSource.MERCHANT_PORTAL)))
                .isEqualTo(EvidenceSource.MERCHANT_PORTAL);
        // INTERNAL is not a source system: it becomes INTERNAL_DERIVED so a reader can tell a fact
        // PDEI inferred from a fact a source system asserted.
        assertThat(DerivedEvidenceService.sourceOf(
                Events.withSource(delivery(), EventSource.INTERNAL)))
                .isEqualTo(EvidenceSource.INTERNAL_DERIVED);
    }

    @Test
    @DisplayName("the command carries provenance, quality and the source event id")
    void buildsACompleteCommand() {
        EvidenceStubs.Recorder recorder = EvidenceStubs.recorder();
        DerivedEvidenceService service = new DerivedEvidenceService(recorder.service());
        CanonicalEvent event = Events.withSource(delivery(), EventSource.LOGISTICS);

        service.derive(event, EvidenceType.DELIVERY_PROOF, "TX-82918", "SHP-771", "delivered");

        CreateEvidenceCommand command = recorder.only();
        assertThat(command.type()).isEqualTo(EvidenceType.DELIVERY_PROOF);
        assertThat(command.source()).isEqualTo(EvidenceSource.LOGISTICS);
        assertThat(command.merchantId()).isEqualTo(Events.MERCHANT_ID);
        assertThat(command.transactionId()).isEqualTo("TX-82918");
        assertThat(command.relatedEntityId()).isEqualTo("SHP-771");
        assertThat(command.sourceEventId()).isEqualTo(event.eventId());
        assertThat(command.observedAt()).isEqualTo(DELIVERED_AT);
        assertThat(command.contentType()).isEqualTo("application/json");
        assertThat(command.filename()).isEqualTo("delivery_proof-SHP-771.json");
        assertThat(command.provenanceVerified()).isTrue();
        assertThat(command.qualityScore()).isEqualTo(1.0d);
        assertThat(command.actor()).isEqualTo(DerivedEvidenceService.ACTOR);
    }

    @Test
    @DisplayName("a self-reported merchant fact is scored lower and not marked provenance-verified")
    void scoresSelfReportedFactsLower() {
        EvidenceStubs.Recorder recorder = EvidenceStubs.recorder();
        DerivedEvidenceService service = new DerivedEvidenceService(recorder.service());

        service.derive(Events.withSource(delivery(), EventSource.MERCHANT_PORTAL),
                EvidenceType.DELIVERY_PROOF, "TX-82918", "SHP-771", "delivered");

        CreateEvidenceCommand command = recorder.only();
        assertThat(command.provenanceVerified()).isFalse();
        assertThat(command.qualityScore()).isEqualTo(0.7d);
    }

    @Test
    @DisplayName("no transaction means no evidence: evidence always belongs to a transaction")
    void skipsWithoutATransaction() {
        EvidenceStubs.Recorder recorder = EvidenceStubs.recorder();
        DerivedEvidenceService service = new DerivedEvidenceService(recorder.service());

        assertThat(service.derive(delivery(), EvidenceType.DELIVERY_PROOF, null, "SHP-771", "x"))
                .isNull();
        assertThat(recorder.commands()).isEmpty();
    }

    @Test
    @DisplayName("an unavailable EvidenceService degrades to a warning, never a failed projection")
    void degradesWithoutAnEvidenceService() {
        DerivedEvidenceService service = new DerivedEvidenceService(null);

        assertThat(service.derive(delivery(), EvidenceType.DELIVERY_PROOF, "TX-82918", "SHP-771",
                "delivered")).isNull();
    }
}
