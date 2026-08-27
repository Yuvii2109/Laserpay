package com.laserpay.pdei.normalization.upcast;

import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.kafka.EventHeaders;
import com.laserpay.pdei.normalization.RawEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpcasterChainTest {

    private final UpcasterChain chain = new UpcasterChain(List.of(
            new LegacyMinorUnitsUpcaster("INR"),
            new RetiredSourceEventTypeUpcaster()));

    @Test
    @DisplayName("a current-shape envelope passes through untouched")
    void leavesCurrentShapeAlone() {
        RawEventEnvelope raw = RawEvents.of("raw-1", "PSP", "payment_intent.succeeded", """
                { "amount": { "amountMinor": 129900, "currency": "INR" } }
                """, Map.of(EventHeaders.SCHEMA_VERSION, "1"));

        assertThat(chain.upcast(raw)).isSameAs(raw);
    }

    @Test
    @DisplayName("legacy *_cents fields become canonical minor-unit money objects")
    void migratesLegacyMinorUnits() {
        RawEventEnvelope raw = RawEvents.of("raw-2", "PSP", "payment.captured", """
                { "amount_cents": 129900, "currency": "inr",
                  "lines": [ { "line_total_cents": 99900 } ] }
                """, Map.of());

        RawEventEnvelope migrated = chain.upcast(raw);

        assertThat(migrated.body().path("amount").path("amountMinor").asLong()).isEqualTo(129_900L);
        assertThat(migrated.body().path("amount").path("currency").asText()).isEqualTo("INR");
        assertThat(migrated.body().path("lines").get(0).path("line_total").path("amountMinor").asLong())
                .isEqualTo(99_900L);
        // The original scalar is retained: nothing is discarded during an upcast.
        assertThat(migrated.body().path("amount_cents").asLong()).isEqualTo(129_900L);
        assertThat(SchemaVersions.read(migrated)).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired source event name is renamed to the current one")
    void renamesRetiredSourceEventTypes() {
        RawEventEnvelope raw = RawEvents.of("raw-3", "PSP", "charge.succeeded", "{}", Map.of());

        assertThat(chain.upcast(raw).sourceEventType()).isEqualTo("payment_intent.succeeded");
    }

    @Test
    @DisplayName("both migrations run in one pass when both apply")
    void appliesMultipleStepsInOnePass() {
        RawEventEnvelope raw = RawEvents.of("raw-4", "PSP", "charge.succeeded", """
                { "amount_cents": 5000, "currency": "usd" }
                """, Map.of());

        RawEventEnvelope migrated = chain.upcast(raw);

        assertThat(migrated.sourceEventType()).isEqualTo("payment_intent.succeeded");
        assertThat(migrated.body().path("amount").path("amountMinor").asLong()).isEqualTo(5_000L);
        assertThat(migrated.body().path("amount").path("currency").asText()).isEqualTo("USD");
    }

    @Test
    @DisplayName("upcastAndStamp always labels the envelope with the current schema version")
    void stampsCurrentVersion() {
        RawEventEnvelope raw = RawEvents.of("raw-5", "PSP", "payment_intent.succeeded", "{}", Map.of());

        assertThat(SchemaVersions.read(raw)).isEqualTo(SchemaVersions.UNVERSIONED);
        assertThat(SchemaVersions.read(chain.upcastAndStamp(raw))).isEqualTo(1);
    }

    @Test
    @DisplayName("the chain terminates: upcasters are idempotent by construction")
    void terminates() {
        RawEventEnvelope raw = RawEvents.of("raw-6", "PSP", "charge.succeeded", """
                { "amount_cents": 100, "currency": "INR" }
                """, Map.of());

        RawEventEnvelope once = chain.upcast(raw);
        RawEventEnvelope twice = chain.upcast(once);

        assertThat(twice).isSameAs(once);
    }

    @Test
    @DisplayName("an empty chain is a no-op")
    void emptyChainIsNoop() {
        RawEventEnvelope raw = RawEvents.of("raw-7", "PSP", "charge.succeeded", "{}", Map.of());

        assertThat(UpcasterChain.empty().upcast(raw)).isSameAs(raw);
    }
}
