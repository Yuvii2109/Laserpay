package com.laserpay.pdei.core.readiness;

import com.laserpay.pdei.common.domain.EvidenceStatus;
import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.GapSeverity;
import com.laserpay.pdei.common.domain.GapType;
import com.laserpay.pdei.common.domain.RequirementStrength;
import com.laserpay.pdei.core.TestFixtures;
import com.laserpay.pdei.core.model.ContradictionView;
import com.laserpay.pdei.core.model.EvidenceView;
import com.laserpay.pdei.core.model.ReadinessGap;
import com.laserpay.pdei.core.policy.RequirementSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GapDetectorTest {

    private static final Instant NOW = TestFixtures.NOW;
    private final GapDetector detector = new GapDetector(7, 0.5d);

    private List<ReadinessGap> detect(List<RequirementSpec> requirements, List<EvidenceView> evidence) {
        return detector.detect(TestFixtures.TRANSACTION, requirements, evidence, List.of(), NOW);
    }

    @Test
    @DisplayName("a required type with no evidence at all produces MISSING")
    void missing() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF)),
                List.of());

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).type()).isEqualTo(GapType.MISSING);
        assertThat(gaps.get(0).evidenceType()).isEqualTo(EvidenceType.DELIVERY_PROOF);
        assertThat(gaps.get(0).severity()).isEqualTo(GapSeverity.HIGH);
    }

    @Test
    @DisplayName("MISSING on a recommended requirement is only MEDIUM")
    void missingRecommendedIsMedium() {
        List<ReadinessGap> gaps = detect(
                List.of(TestFixtures.recommended(EvidenceType.CUSTOMER_COMMUNICATION)), List.of());

        assertThat(gaps).singleElement()
                .extracting(ReadinessGap::severity).isEqualTo(GapSeverity.MEDIUM);
    }

    @Test
    @DisplayName("evidence past its expiry produces EXPIRED even when the status event has not arrived")
    void expiredByWallClock() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.INVOICE)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.INVOICE)
                        .expiresAt(NOW.minusSeconds(60)).build()));

        assertThat(gaps).anyMatch(gap -> gap.type() == GapType.EXPIRED);
    }

    @Test
    @DisplayName("expiry inside the seven day window produces EXPIRING_SOON, not EXPIRED")
    void expiringSoon() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.MERCHANT_POLICY)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.MERCHANT_POLICY)
                        .expiresAt(NOW.plusSeconds(3 * 24 * 3600)).build()));

        assertThat(gaps).extracting(ReadinessGap::type).containsExactly(GapType.EXPIRING_SOON);
        assertThat(gaps.get(0).expiresAt()).isEqualTo(NOW.plusSeconds(3 * 24 * 3600));
    }

    @Test
    @DisplayName("expiry beyond the window is not reported at all")
    void expiryOutsideWindowIsSilent() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.MERCHANT_POLICY)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.MERCHANT_POLICY)
                        .expiresAt(NOW.plusSeconds(30L * 24 * 3600)).build()));

        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("evidence with no source event produces UNVERIFIABLE_PROVENANCE, CRITICAL on mandatory")
    void unverifiableProvenance() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF)
                        .sourceEventId(null).provenanceVerified(false).build()));

        assertThat(gaps).filteredOn(gap -> gap.type() == GapType.UNVERIFIABLE_PROVENANCE)
                .singleElement()
                .extracting(ReadinessGap::severity).isEqualTo(GapSeverity.CRITICAL);
    }

    @Test
    @DisplayName("a quality score below the floor produces LOW_QUALITY")
    void lowQuality() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.SHIPPING_RECORD)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.SHIPPING_RECORD).quality(0.2d).build()));

        assertThat(gaps).extracting(ReadinessGap::type).containsExactly(GapType.LOW_QUALITY);
    }

    @Test
    @DisplayName("two live versions of the same chain produce VERSION_CONFLICT")
    void versionConflict() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.INVOICE)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.INVOICE).version(1).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.INVOICE).version(2)
                                .parent("EV-1").build()));

        assertThat(gaps).extracting(ReadinessGap::type).contains(GapType.VERSION_CONFLICT);
    }

    @Test
    @DisplayName("a properly superseded parent produces no version conflict")
    void supersededParentIsClean() {
        List<ReadinessGap> gaps = detect(List.of(TestFixtures.mandatory(EvidenceType.INVOICE)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.INVOICE).version(1)
                                .status(EvidenceStatus.SUPERSEDED).build(),
                        TestFixtures.evidence("EV-2", EvidenceType.INVOICE).version(2)
                                .parent("EV-1").build()));

        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("every contradiction becomes one CONTRADICTORY gap carrying its severity")
    void contradictionsBecomeGaps() {
        List<ReadinessGap> gaps = detector.detect(TestFixtures.TRANSACTION,
                List.of(TestFixtures.mandatory(EvidenceType.PAYMENT_PROOF)),
                List.of(TestFixtures.evidence("EV-1", EvidenceType.PAYMENT_PROOF).build()),
                List.of(ContradictionView.of("EV-1", "EV-2", "deliveredAt", "before dispatch",
                                GapSeverity.HIGH, "a", "b", NOW),
                        ContradictionView.of("EV-3", "EV-4", "refundAmount", "exceeds capture",
                                GapSeverity.CRITICAL, 10, 5, NOW)),
                NOW);

        assertThat(gaps).filteredOn(gap -> gap.type() == GapType.CONTRADICTORY)
                .extracting(ReadinessGap::severity)
                .containsExactly(GapSeverity.HIGH, GapSeverity.CRITICAL);
    }

    @Test
    @DisplayName("gap ids are deterministic so recomputation is idempotent")
    void gapIdsAreDeterministic() {
        List<RequirementSpec> requirements = List.of(TestFixtures.mandatory(EvidenceType.DELIVERY_PROOF));

        List<ReadinessGap> first = detect(requirements, List.of());
        List<ReadinessGap> second = detect(requirements, List.of());

        assertThat(first.get(0).gapId()).isEqualTo(second.get(0).gapId());
        assertThat(first.get(0).gapId()).startsWith("GAP-");
    }

    @Test
    @DisplayName("PROHIBITED requirements are not gap sources")
    void prohibitedIsNotAGap() {
        RequirementSpec prohibited = new RequirementSpec(EvidenceType.DEVICE_FINGERPRINT,
                RequirementStrength.PROHIBITED, 0, null, false, 0.0d, null);

        assertThat(detect(List.of(prohibited), List.of())).isEmpty();
    }
}
