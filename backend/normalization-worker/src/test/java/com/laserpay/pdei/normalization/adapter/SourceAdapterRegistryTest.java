package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.normalization.RawEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceAdapterRegistryTest {

    private SourceAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SourceAdapterRegistry(List.of(
                new PspAdapter("INR"),
                new OrderSystemAdapter("INR"),
                new LogisticsAdapter("INR"),
                new CrmAdapter("INR"),
                new SimulatorAdapter("INR"),
                new MerchantPortalAdapter("INR")));
    }

    @Test
    @DisplayName("resolves by canonical name, by vendor alias and regardless of separators")
    void resolvesAliases() {
        assertThat(registry.require(RawEvents.of("PSP", "payment.captured", "{}")))
                .isInstanceOf(PspAdapter.class);
        assertThat(registry.require(RawEvents.of("stripe", "payment.captured", "{}")))
                .isInstanceOf(PspAdapter.class);
        assertThat(registry.require(RawEvents.of("order-system", "order.created", "{}")))
                .isInstanceOf(OrderSystemAdapter.class);
        assertThat(registry.require(RawEvents.of("Order_System", "order.created", "{}")))
                .isInstanceOf(OrderSystemAdapter.class);
        assertThat(registry.require(RawEvents.of("shiprocket", "shipment.delivered", "{}")))
                .isInstanceOf(LogisticsAdapter.class);
        assertThat(registry.require(RawEvents.of("zendesk", "email.sent", "{}")))
                .isInstanceOf(CrmAdapter.class);
        assertThat(registry.require(RawEvents.of("SIMULATOR", "PaymentCaptured", "{}")))
                .isInstanceOf(SimulatorAdapter.class);
        assertThat(registry.require(RawEvents.of("merchant_portal", "communication.logged", "{}")))
                .isInstanceOf(MerchantPortalAdapter.class);
    }

    @Test
    @DisplayName("an unknown source system is unmappable, and says what it does know")
    void refusesUnknownSourceSystem() {
        assertThatThrownBy(() -> registry.require(RawEvents.of("mystery-erp", "thing.happened", "{}")))
                .isInstanceOf(UnmappableEventException.class)
                .hasMessageContaining("no SourceAdapter is registered")
                .hasMessageContaining("ORDER_SYSTEM");

        assertThat(registry.find(RawEvents.of("mystery-erp", "thing.happened", "{}"))).isEmpty();
    }

    @Test
    @DisplayName("two adapters cannot claim the same alias")
    void rejectsAmbiguousAliases() {
        assertThatThrownBy(() -> new SourceAdapterRegistry(List.of(
                new PspAdapter("INR"),
                new DuplicateAliasAdapter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe");
    }

    @Test
    @DisplayName("describes itself: source system to accepted source event types")
    void describesItself() {
        assertThat(registry.sourceSystems()).contains("PSP", "ORDER_SYSTEM", "LOGISTICS", "CRM",
                "SIMULATOR", "MERCHANT_PORTAL");
        assertThat(registry.describe()).containsKeys("PSP", "LOGISTICS");
    }

    /** Deliberately collides with {@link PspAdapter}'s vendor alias. */
    private static final class DuplicateAliasAdapter extends AbstractSourceAdapter {

        private DuplicateAliasAdapter() {
            super("OTHER", java.util.Set.of("stripe"), java.util.Map.of(), "INR");
        }

        @Override
        public com.laserpay.pdei.common.event.EventSource eventSource() {
            return com.laserpay.pdei.common.event.EventSource.INTERNAL;
        }

        @Override
        protected com.laserpay.pdei.common.event.CanonicalEvent map(
                com.laserpay.pdei.common.event.RawEventEnvelope raw,
                com.laserpay.pdei.common.event.EventType eventType,
                java.time.Instant observedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
