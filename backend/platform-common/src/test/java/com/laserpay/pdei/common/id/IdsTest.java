package com.laserpay.pdei.common.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void staticFactoriesCarryTheContractPrefixes() {
        assertThat(Ids.merchant()).startsWith(IdPrefix.MERCHANT);
        assertThat(Ids.transaction()).startsWith(IdPrefix.TRANSACTION);
        assertThat(Ids.evidence()).startsWith(IdPrefix.EVIDENCE);
        assertThat(Ids.disputeCase()).startsWith(IdPrefix.CASE);
        assertThat(Ids.investigation()).startsWith(IdPrefix.INVESTIGATION);
        assertThat(Ids.simulation()).startsWith(IdPrefix.SIMULATION);
    }

    @Test
    void generatedIdsFitTheVarchar64PrimaryKeyAndUseTheSafeAlphabet() {
        for (String prefix : IdPrefix.ALL) {
            String id = Ids.withPrefix(prefix);
            assertThat(id).hasSizeLessThanOrEqualTo(64);
            assertThat(id.substring(prefix.length())).matches("[0-9A-HJKMNP-TV-Z]{8}");
        }
    }

    @Test
    void seededGeneratorsAreReproducible() {
        SeededIdGenerator first = Ids.withSeed(42L);
        SeededIdGenerator second = Ids.withSeed(42L);

        List<String> fromFirst = new ArrayList<>();
        List<String> fromSecond = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            fromFirst.add(first.transaction());
            fromFirst.add(first.eventId());
            fromSecond.add(second.transaction());
            fromSecond.add(second.eventId());
        }

        assertThat(fromFirst).isEqualTo(fromSecond);
    }

    @Test
    void differentSeedsDiverge() {
        assertThat(Ids.withSeed(1L).evidence()).isNotEqualTo(Ids.withSeed(2L).evidence());
    }

    @Test
    void seededEventIdsAreValidVersion4Uuids() {
        String id = Ids.withSeed(7L).eventId();

        UUID parsed = UUID.fromString(id);
        assertThat(parsed.version()).isEqualTo(4);
        assertThat(parsed.variant()).isEqualTo(2);
        assertThat(Ids.eventId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    @Test
    void collisionsAreRareEnoughForTheSimulatorWorkload() {
        SeededIdGenerator generator = Ids.withSeed(2026L);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.transaction());
        }
        // The 8-character body carries 40 bits, so the birthday expectation over 10k draws is well
        // under one collision. Allow exactly one so the assertion states the property rather than
        // pinning one particular seed's luck.
        assertThat(ids).hasSizeGreaterThanOrEqualTo(9_999);
    }

    @Test
    void hasPrefixIsNullSafe() {
        assertThat(Ids.hasPrefix("EV-ABCDEFGH", IdPrefix.EVIDENCE)).isTrue();
        assertThat(Ids.hasPrefix("EV-ABCDEFGH", IdPrefix.CASE)).isFalse();
        assertThat(Ids.hasPrefix(null, IdPrefix.EVIDENCE)).isFalse();
        assertThat(Ids.hasPrefix("EV-1", null)).isFalse();
    }

    @Test
    void prefixOfRecognisesEveryContractPrefix() {
        assertThat(IdPrefix.prefixOf("CASE-ABCDEFGH")).isEqualTo(IdPrefix.CASE);
        assertThat(IdPrefix.prefixOf("TX-ABCDEFGH")).isEqualTo(IdPrefix.TRANSACTION);
        assertThat(IdPrefix.prefixOf("nope")).isNull();
        assertThat(IdPrefix.prefixOf(null)).isNull();
    }
}
