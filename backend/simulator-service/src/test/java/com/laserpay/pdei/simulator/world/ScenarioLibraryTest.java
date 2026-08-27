package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.domain.EvidenceType;
import com.laserpay.pdei.common.domain.ReadinessBand;
import com.laserpay.pdei.common.error.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The curated scenarios are the demo's contract, so this test guards their identity (keys,
 * seeds, declared expectations) and checks that each one actually generates the world its
 * description claims.
 */
class ScenarioLibraryTest {

    private final WorldGenerator generator = new WorldGenerator();

    @Test
    @DisplayName("every documented scenario key exists, in demo order")
    void allKeysArePresent() {
        assertThat(ScenarioLibrary.keys()).containsExactly(
                "clean-delivery-defendable",
                "missing-delivery-proof",
                "contradictory-delivery-dates",
                "expired-policy-evidence",
                "duplicate-charge-dispute",
                "partial-refund-dispute",
                "multi-shipment-order",
                "late-evidence-arrival",
                "subscription-cancelled-dispute",
                "high-value-urgent-deadline");
    }

    @Test
    @DisplayName("every scenario declares a complete, self-consistent expectation")
    void everyScenarioDeclaresItsOutcome() {
        for (Scenario scenario : ScenarioLibrary.all()) {
            assertThat(scenario.title()).isNotBlank();
            assertThat(scenario.description()).isNotBlank();
            assertThat(scenario.demoNote()).isNotBlank();
            assertThat(scenario.reasonCode()).isNotNull();
            assertThat(scenario.expectedBand()).isNotNull();
            assertThat(scenario.expectedAiPath()).isNotNull();
            assertThat(scenario.expectedClassification()).isNotNull();
            assertThat(scenario.expectedAction()).isNotNull();

            assertThat(scenario.expectedScoreMin())
                    .as("%s score range", scenario.key())
                    .isBetween(0, 100)
                    .isLessThanOrEqualTo(scenario.expectedScoreMax());
            assertThat(scenario.expectedScoreMax()).isBetween(0, 100);

            // The declared band must agree with the declared score range, or the demo would
            // display two different answers to the same question.
            assertThat(ReadinessBand.fromScore(scenario.expectedScoreMin()))
                    .as("%s band at score min", scenario.key())
                    .isEqualTo(scenario.expectedBand());
            assertThat(ReadinessBand.fromScore(scenario.expectedScoreMax()))
                    .as("%s band at score max", scenario.key())
                    .isEqualTo(scenario.expectedBand());

            // Every scenario disputes every transaction, so the outcome is never a sampling
            // accident, and every scenario forces its reason code.
            assertThat(scenario.spec().disputeRateBps()).isEqualTo(FailureMix.FULL_BPS);
            assertThat(scenario.spec().forcedReasonCode()).isEqualTo(scenario.reasonCode());
            assertThat(scenario.spec().scenarioKey()).isEqualTo(scenario.key());
        }
    }

    @Test
    @DisplayName("scenario seeds are distinct, so two scenarios never generate the same world")
    void seedsAreDistinct() {
        List<Long> seeds = ScenarioLibrary.all().stream().map(s -> s.spec().seed()).toList();
        assertThat(Set.copyOf(seeds)).hasSameSizeAs(seeds);
    }

    @Test
    @DisplayName("both AI paths are represented, which is the whole admission-control claim")
    void bothAiPathsAreCovered() {
        List<AiPath> paths = ScenarioLibrary.all().stream().map(Scenario::expectedAiPath).toList();
        assertThat(paths).contains(AiPath.DETERMINISTIC, AiPath.AMBIGUOUS);
    }

    @Test
    @DisplayName("clean-delivery-defendable really does produce a delivery proof")
    void cleanScenarioProducesDeliveryProof() {
        GeneratedWorld world = generate(ScenarioLibrary.CLEAN_DELIVERY_DEFENDABLE);

        assertThat(evidenceTypes(world)).contains(
                EvidenceType.PAYMENT_PROOF,
                EvidenceType.INVOICE,
                EvidenceType.ORDER_RECORD,
                EvidenceType.SHIPPING_RECORD,
                EvidenceType.DELIVERY_PROOF,
                EvidenceType.MERCHANT_POLICY,
                EvidenceType.CUSTOMER_COMMUNICATION);
        assertThat(world.disputedTransactionIds()).hasSameSizeAs(world.transactionIds());
    }

    @Test
    @DisplayName("missing-delivery-proof really does omit every delivery proof")
    void missingDeliveryProofOmitsIt() {
        GeneratedWorld world = generate(ScenarioLibrary.MISSING_DELIVERY_PROOF);

        assertThat(evidenceTypes(world))
                .contains(EvidenceType.SHIPPING_RECORD)
                .doesNotContain(EvidenceType.DELIVERY_PROOF);
    }

    @Test
    @DisplayName("duplicate-charge-dispute really does emit every event twice")
    void duplicateScenarioDuplicatesEverything() {
        GeneratedWorld world = generate(ScenarioLibrary.DUPLICATE_CHARGE_DISPUTE);

        assertThat(world.count(GeneratedWorld.COUNT_DUPLICATE_EVENTS)).isPositive();
        long distinctIds = world.events().stream()
                .map(event -> event.envelope().rawEventId())
                .distinct()
                .count();
        assertThat(distinctIds).isLessThan(world.eventCount());
    }

    @Test
    @DisplayName("late-evidence-arrival delays the delivery proof past the dispute")
    void lateEvidenceArrivesAfterTheDispute() {
        GeneratedWorld world = generate(ScenarioLibrary.LATE_EVIDENCE_ARRIVAL);

        var latestDisputeObservedAt = world.events().stream()
                .filter(SimEvent::isDispute)
                .map(SimEvent::observedAt)
                .max(java.time.Instant::compareTo)
                .orElseThrow();
        boolean proofLandsAfterADispute = world.events().stream()
                .filter(event -> event.artifact() != null
                        && event.artifact().type() == EvidenceType.DELIVERY_PROOF)
                .anyMatch(event -> event.observedAt().isAfter(latestDisputeObservedAt)
                        || event.observedAt().isAfter(event.occurredAt().plusSeconds(86_400 * 30)));
        assertThat(proofLandsAfterADispute).isTrue();
    }

    @Test
    @DisplayName("high-value-urgent-deadline floors the transaction amount and tightens the deadline")
    void highValueScenarioIsHighValue() {
        Scenario scenario = ScenarioLibrary.require(ScenarioLibrary.HIGH_VALUE_URGENT_DEADLINE);
        assertThat(scenario.spec().minAmountMinor()).isEqualTo(1_299_900L);
        assertThat(scenario.spec().disputeDeadlineDays()).isEqualTo(1);

        GeneratedWorld world = generator.generate(scenario.spec());
        // Every transaction clears the floor, in minor units, with no floating point anywhere.
        assertThat(world.grossValue().amountMinor())
                .isGreaterThanOrEqualTo(1_299_900L * world.transactionIds().size());
    }

    @Test
    @DisplayName("an unknown scenario key is a NotFoundException, not a silent default")
    void unknownKeyIsRejected() {
        assertThat(ScenarioLibrary.find("nope")).isEmpty();
        assertThatThrownBy(() -> ScenarioLibrary.require("nope"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("running a scenario twice produces the same world")
    void scenariosAreReproducible() {
        for (Scenario scenario : ScenarioLibrary.all()) {
            GeneratedWorld first = generator.generate(scenario.spec());
            GeneratedWorld second = generator.generate(scenario.spec());
            assertThat(second.evidenceIds())
                    .as("%s evidence ids", scenario.key())
                    .isEqualTo(first.evidenceIds());
            assertThat(second.counts())
                    .as("%s counts", scenario.key())
                    .isEqualTo(first.counts());
        }
    }

    private GeneratedWorld generate(String key) {
        return generator.generate(ScenarioLibrary.require(key).spec());
    }

    private static List<EvidenceType> evidenceTypes(GeneratedWorld world) {
        return world.artifacts().stream().map(SyntheticArtifact::type).distinct().toList();
    }
}
