package com.laserpay.pdei.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoresAndTextTest {

    @Test
    @DisplayName("rounding is half up, as the readiness formula requires")
    void roundsHalfUp() {
        assertThat(Scores.roundHalfUp(87.5d)).isEqualTo(88);
        assertThat(Scores.roundHalfUp(87.4999d)).isEqualTo(87);
        assertThat(Scores.roundHalfUp(0.5d)).isEqualTo(1);
        assertThat(Scores.roundHalfUp(89.5d)).isEqualTo(90);
    }

    @Test
    @DisplayName("clamping keeps a score inside its band range")
    void clamps() {
        assertThat(Scores.roundAndClamp(-40.0d, 0, 100)).isZero();
        assertThat(Scores.roundAndClamp(140.0d, 0, 100)).isEqualTo(100);
        assertThat(Scores.roundAndClamp(73.5d, 0, 100)).isEqualTo(74);
    }

    @Test
    @DisplayName("address normalisation folds case, punctuation and common abbreviations")
    void normalisesAddresses() {
        assertThat(Text.sameAddress("12 Main Street, Bengaluru 560001",
                "12  main st.,  bengaluru - 560001")).isTrue();
        assertThat(Text.sameAddress("12 Main Street", "99 Other Road")).isFalse();
    }

    @Test
    @DisplayName("a missing address is a provenance gap, not a contradiction")
    void missingAddressIsNotAMismatch() {
        assertThat(Text.sameAddress(null, "12 Main Street")).isTrue();
        assertThat(Text.sameAddress("", "12 Main Street")).isTrue();
    }

    @Test
    @DisplayName("free text becomes a safe AND-ed tsquery with a prefix match on the last token")
    void buildsTsQuery() {
        assertThat(Text.toTsQuery("delivery proof")).isEqualTo("delivery & proof:*");
        assertThat(Text.toTsQuery("  invoice  ")).isEqualTo("invoice:*");
        assertThat(Text.toTsQuery("drop table; --")).isEqualTo("drop & table:*");
        assertThat(Text.toTsQuery("")).isEmpty();
        assertThat(Text.toTsQuery(null)).isEmpty();
    }
}
