package com.laserpay.pdei.normalization.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadsTest {

    @Test
    @DisplayName("resolves dotted paths, including array indices")
    void resolvesPaths() {
        JsonNode node = Json.readTree("""
                { "data": { "object": { "id": "pi_1" } }, "lines": [ { "sku": "A" } ] }
                """);

        assertThat(Payloads.text(node, "data.object.id")).isEqualTo("pi_1");
        assertThat(Payloads.text(node, "lines.0.sku")).isEqualTo("A");
        assertThat(Payloads.text(node, "missing.path", "data.object.id")).isEqualTo("pi_1");
        assertThat(Payloads.text(node, "nope")).isNull();
    }

    @Test
    @DisplayName("accepts ISO-8601, epoch seconds and epoch milliseconds")
    void parsesTimestamps() {
        JsonNode node = Json.readTree("""
                { "iso": "2026-08-26T09:00:00Z", "secs": 1787644800, "millis": 1787644800123,
                  "isoText": "1787644800" }
                """);

        assertThat(Payloads.instant(node, "iso")).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        assertThat(Payloads.instant(node, "secs")).isEqualTo(Instant.ofEpochSecond(1787644800L));
        assertThat(Payloads.instant(node, "millis")).isEqualTo(Instant.ofEpochMilli(1787644800123L));
        assertThat(Payloads.instant(node, "isoText")).isEqualTo(Instant.ofEpochSecond(1787644800L));
        assertThat(Payloads.instantOr(node, Instant.EPOCH, "absent")).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("reads every accepted money shape into minor units")
    void readsMoneyShapes() {
        JsonNode node = Json.readTree("""
                {
                  "canonical": { "amountMinor": 129900, "currency": "INR" },
                  "snake":     { "amount_minor": 129900, "currency": "inr" },
                  "psp":       { "amount": 129900, "currency": "INR" },
                  "decimal":   { "amount": "1299.00", "currency": "INR" },
                  "scalar":    129900
                }
                """);

        assertThat(Payloads.money(node, "INR", "canonical")).isEqualTo(Money.of(129_900L, "INR"));
        assertThat(Payloads.money(node, "INR", "snake")).isEqualTo(Money.of(129_900L, "INR"));
        assertThat(Payloads.money(node, "INR", "psp")).isEqualTo(Money.of(129_900L, "INR"));
        assertThat(Payloads.money(node, "INR", "decimal")).isEqualTo(Money.of(129_900L, "INR"));
        assertThat(Payloads.money(node, "INR", "scalar")).isEqualTo(Money.of(129_900L, "INR"));
        assertThat(Payloads.money(node, "INR", "absent")).isNull();
    }

    @Test
    @DisplayName("a floating-point monetary literal is rejected outright")
    void rejectsFloatingPointMoney() {
        JsonNode node = Json.readTree("{ \"amount\": { \"amount\": 1299.00, \"currency\": \"INR\" } }");

        assertThatThrownBy(() -> Payloads.money(node, "INR", "amount"))
                .isInstanceOf(MonetaryPrecisionException.class)
                .hasMessageContaining("floating-point");
    }

    @Test
    @DisplayName("decimal strings convert by integer digit shifting, and never round")
    void convertsDecimalStrings() {
        assertThat(Payloads.minorFromDecimalText("1299.00", "INR")).isEqualTo(129_900L);
        assertThat(Payloads.minorFromDecimalText("1299", "INR")).isEqualTo(129_900L);
        assertThat(Payloads.minorFromDecimalText("1299.5", "INR")).isEqualTo(129_950L);
        assertThat(Payloads.minorFromDecimalText("-12.34", "USD")).isEqualTo(-1_234L);
        assertThat(Payloads.minorFromDecimalText("0.01", "USD")).isEqualTo(1L);

        assertThatThrownBy(() -> Payloads.minorFromDecimalText("12.345", "USD"))
                .isInstanceOf(MonetaryPrecisionException.class)
                .hasMessageContaining("fraction digits");
        assertThatThrownBy(() -> Payloads.minorFromDecimalText("twelve", "USD"))
                .isInstanceOf(MonetaryPrecisionException.class);
    }

    @Test
    @DisplayName("currency codes are uppercased and fall back when the source omits one")
    void normalizesCurrency() {
        assertThat(Payloads.normalizeCurrency("inr", "USD")).isEqualTo("INR");
        assertThat(Payloads.normalizeCurrency(null, "usd")).isEqualTo("USD");
        assertThat(Payloads.normalizeCurrency(" ", null)).isNull();
    }

    @Test
    @DisplayName("writers omit absent values rather than emitting nulls")
    void writersOmitAbsentValues() {
        var target = Payloads.object();
        Payloads.putText(target, "a", null);
        Payloads.putText(target, "b", "  ");
        Payloads.putInstant(target, "c", null);
        Payloads.putMoney(target, "d", null);
        Payloads.putText(target, "e", "value");

        assertThat(target.size()).isEqualTo(1);
        assertThat(target.path("e").asText()).isEqualTo("value");
    }
}
