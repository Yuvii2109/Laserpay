package com.laserpay.pdei.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void plusAddsMinorUnits() {
            assertThat(Money.of(1_299_900, "INR").plus(Money.of(100, "INR")))
                    .isEqualTo(Money.of(1_300_000, "INR"));
        }

        @Test
        void minusSubtractsMinorUnitsAndMayGoNegative() {
            Money result = Money.of(500, "USD").minus(Money.of(1_200, "USD"));
            assertThat(result.amountMinor()).isEqualTo(-700L);
            assertThat(result.isNegative()).isTrue();
            assertThat(result.isPositive()).isFalse();
        }

        @Test
        void multiplyScalesByAWholeFactor() {
            assertThat(Money.of(1_999, "EUR").multiply(3)).isEqualTo(Money.of(5_997, "EUR"));
            assertThat(Money.of(1_999, "EUR").multiply(0)).isEqualTo(Money.zero("EUR"));
        }

        @Test
        void negatedFlipsTheSignAndKeepsTheCurrency() {
            assertThat(Money.of(250, "GBP").negated()).isEqualTo(Money.of(-250, "GBP"));
        }

        @Test
        void arithmeticIsOverflowChecked() {
            Money max = Money.of(Long.MAX_VALUE, "INR");
            assertThatThrownBy(() -> max.plus(Money.of(1, "INR")))
                    .isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> max.multiply(2))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void zeroAndPredicates() {
            assertThat(Money.zero("INR").isZero()).isTrue();
            assertThat(Money.zero("INR").isPositive()).isFalse();
            assertThat(Money.of(1, "INR").isPositive()).isTrue();
        }
    }

    @Nested
    @DisplayName("currency mismatch")
    class Mismatch {

        @Test
        void plusRejectsDifferentCurrencies() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100, "INR").plus(Money.of(100, "USD")))
                    .withMessageContaining("INR")
                    .withMessageContaining("USD");
        }

        @Test
        void minusRejectsDifferentCurrencies() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100, "INR").minus(Money.of(100, "EUR")));
        }

        @Test
        void compareToRejectsDifferentCurrencies() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100, "INR").compareTo(Money.of(100, "JPY")));
        }

        @Test
        void exceptionCarriesBothCurrencies() {
            CurrencyMismatchException e = null;
            try {
                Money.of(1, "INR").plus(Money.of(1, "USD"));
            } catch (CurrencyMismatchException caught) {
                e = caught;
            }
            assertThat(e).isNotNull();
            assertThat(e.left()).isEqualTo("INR");
            assertThat(e.right()).isEqualTo("USD");
        }
    }

    @Nested
    @DisplayName("construction and ordering")
    class Construction {

        @Test
        void currencyIsNormalisedToUpperCase() {
            assertThat(Money.of(10, "inr")).isEqualTo(Money.of(10, "INR"));
            assertThat(Money.of(10, " usd ").currency()).isEqualTo("USD");
        }

        @Test
        void rejectsNonIso4217CurrencyCodes() {
            assertThatThrownBy(() -> Money.of(10, "RUPEE"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.of(10, "IN1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.of(10, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void comparesBySignedMinorUnits() {
            assertThat(Money.of(100, "INR").compareTo(Money.of(99, "INR"))).isPositive();
            assertThat(Money.of(-100, "INR").compareTo(Money.zero("INR"))).isNegative();
            assertThat(Money.of(100, "INR").compareTo(Money.of(100, "inr"))).isZero();
        }
    }

    @Nested
    @DisplayName("display formatting (display only)")
    class Display {

        @ParameterizedTest
        @CsvSource({
                "1299900, INR, 'INR 12,999.00'",
                "0,       INR, 'INR 0.00'",
                "5,       USD, 'USD 0.05'",
                "-2500,   USD, 'USD -25.00'",
                "1234567890, USD, 'USD 12,345,678.90'"
        })
        void formatsTwoDecimalCurrencies(long minor, String currency, String expected) {
            assertThat(Money.of(minor, currency).toDisplayString()).isEqualTo(expected);
        }

        @Test
        void respectsIso4217FractionDigits() {
            // JPY has zero minor digits, KWD has three.
            assertThat(Money.of(5_000, "JPY").toDisplayString()).isEqualTo("JPY 5,000");
            assertThat(Money.of(1_234_567, "KWD").toDisplayString()).isEqualTo("KWD 1,234.567");
        }

        @Test
        void unknownCurrencyCodesFallBackToTwoDigits() {
            assertThat(Money.of(1_050, "ZZZ").toDisplayString()).isEqualTo("ZZZ 10.50");
        }

        @Test
        void toStringStaysMachineReadable() {
            assertThat(Money.of(1_299_900, "INR")).hasToString("1299900 INR");
        }
    }
}
