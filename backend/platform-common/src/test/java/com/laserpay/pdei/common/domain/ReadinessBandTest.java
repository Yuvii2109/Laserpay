package com.laserpay.pdei.common.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReadinessBandTest {

    @ParameterizedTest(name = "score {0} -> {1}")
    @CsvSource({
            // NOT_READY: < 50
            "0,   NOT_READY",
            "1,   NOT_READY",
            "49,  NOT_READY",
            // AT_RISK: 50..74
            "50,  AT_RISK",
            "74,  AT_RISK",
            // NEARLY_READY: 75..89
            "75,  NEARLY_READY",
            "89,  NEARLY_READY",
            // READY: >= 90
            "90,  READY",
            "100, READY"
    })
    void classifiesEveryBoundaryExactlyAsTheContractStates(int score, ReadinessBand expected) {
        assertThat(ReadinessBand.fromScore(score)).isEqualTo(expected);
    }

    @Test
    void bandsAreContiguousAndCoverZeroToOneHundred() {
        for (int score = 0; score <= 100; score++) {
            ReadinessBand band = ReadinessBand.fromScore(score);
            assertThat(score)
                    .as("score %d must fall inside the bounds of %s", score, band)
                    .isBetween(band.minScore(), band.maxScore());
        }
    }

    @Test
    void outOfRangeScoresAreClassifiedWithoutThrowing() {
        assertThat(ReadinessBand.fromScore(-1)).isEqualTo(ReadinessBand.NOT_READY);
        assertThat(ReadinessBand.fromScore(Integer.MIN_VALUE)).isEqualTo(ReadinessBand.NOT_READY);
        assertThat(ReadinessBand.fromScore(101)).isEqualTo(ReadinessBand.READY);
        assertThat(ReadinessBand.fromScore(Integer.MAX_VALUE)).isEqualTo(ReadinessBand.READY);
    }

    @Test
    void onlyTheLowerTwoBandsDemandAttention() {
        assertThat(ReadinessBand.NOT_READY.needsAttention()).isTrue();
        assertThat(ReadinessBand.AT_RISK.needsAttention()).isTrue();
        assertThat(ReadinessBand.NEARLY_READY.needsAttention()).isFalse();
        assertThat(ReadinessBand.READY.needsAttention()).isFalse();
    }

    @Test
    void requirementStrengthWeightsMatchTheScoringFormula() {
        assertThat(RequirementStrength.MANDATORY.weight()).isEqualTo(3);
        assertThat(RequirementStrength.RECOMMENDED.weight()).isEqualTo(2);
        assertThat(RequirementStrength.OPTIONAL.weight()).isEqualTo(1);
        assertThat(RequirementStrength.PROHIBITED.weight()).isZero();
        assertThat(RequirementStrength.MANDATORY.isBlocking()).isTrue();
        assertThat(RequirementStrength.RECOMMENDED.isBlocking()).isFalse();
    }
}
