package com.laserpay.pdei.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.ingestion.model.IngestRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry against the real {@code /schemas/events} files, which the module POM copies onto the
 * classpath.
 *
 * <p>The load-bearing assertion is the last one: <strong>every</strong> {@code EventType} in the
 * shared enum has a payload schema. That is the check that stops the schema directory and the Java
 * enum from drifting apart, which is the failure mode that would let an unvalidated event type in.
 */
class SchemaRegistryTest {

    private IngestionProperties properties;
    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new IngestionProperties();
        // No filesystem directory in a unit test: the classpath copy is the one under test.
        properties.getSchemas().setDirectories(List.of());
        properties.getSchemas().setAliases(Map.of("payment_intent.succeeded", "PaymentCaptured"));
        registry = new SchemaRegistry(properties, Json.mapper());
        registry.reload();
    }

    @Test
    @DisplayName("the envelope schemas are registered under their file stems")
    void loadsEnvelopeSchemas() {
        assertThat(registry.envelopeSchema()).isPresent();
        assertThat(registry.canonicalEventSchema()).isPresent();
        assertThat(registry.findByName(SchemaRegistry.RAW_EVENT_SCHEMA)).isPresent();
    }

    @Test
    @DisplayName("every canonical EventType has a payload schema - the drift check")
    void everyEventTypeHasASchema() {
        for (EventType type : EventType.values()) {
            assertThat(registry.findByEventType(type.name()))
                    .as("payload schema for EventType.%s", type.name())
                    .isPresent();
        }
    }

    @Test
    @DisplayName("lookup folds separators and case, so payment-created and PaymentCreated agree")
    void lookupIsSeparatorInsensitive() {
        RegisteredSchema byPascalCase = registry.findByEventType("PaymentCreated").orElseThrow();
        RegisteredSchema byKebabCase = registry.findByEventType("payment-created").orElseThrow();
        RegisteredSchema bySnakeCase = registry.findByEventType("payment_created").orElseThrow();

        assertThat(byKebabCase.name()).isEqualTo(byPascalCase.name());
        assertThat(bySnakeCase.name()).isEqualTo(byPascalCase.name());
        assertThat(byPascalCase.eventType()).isEqualTo("PaymentCreated");
        assertThat(byPascalCase.aggregateType()).isEqualTo("PAYMENT");
    }

    @Test
    @DisplayName("a configured alias maps a source vocabulary onto a canonical schema")
    void appliesAliases() {
        assertThat(registry.findByEventType("payment_intent.succeeded"))
                .isPresent()
                .get()
                .extracting(RegisteredSchema::eventType)
                .isEqualTo("PaymentCaptured");
    }

    @Test
    @DisplayName("internal event payloads are registered but flagged as not externally ingestible")
    void marksInternalSchemas() {
        assertThat(registry.findByEventType("EvidenceAdded").orElseThrow().isExternal()).isFalse();
        assertThat(registry.findByEventType("PaymentCaptured").orElseThrow().isExternal()).isTrue();
    }

    @Test
    @DisplayName("descriptors expose the required fields an adapter author needs")
    void describesRequiredFields() {
        var descriptor = registry.descriptors().stream()
                .filter(d -> "shipment-delivered".equals(d.name()))
                .findFirst()
                .orElseThrow();

        assertThat(descriptor.eventType()).isEqualTo("ShipmentDelivered");
        assertThat(descriptor.requiredFields()).contains("shipmentId", "deliveredAt");
        assertThat(descriptor.location()).isNotBlank();
    }

    @Test
    @DisplayName("the validator reports the exact field path of a money violation")
    void validatorReportsFieldPaths() {
        RawEventValidator validator = new RawEventValidator(registry, properties, Json.mapper());

        ValidationOutcome outcome = validator.validate(new IngestRequest(
                null, "psp-adapter", "PaymentCaptured", "MER-0001", null, null, null, null, null, null,
                Json.readTree("""
                        {"paymentId":"PAY-1","transactionId":"TX-1",
                         "capturedAmount":{"amountMinor":1000,"currency":"inr"},
                         "capturedAt":"2026-08-26T10:15:30Z"}
                        """)));

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.schemaName()).isEqualTo("payment-captured");
        assertThat(outcome.errors())
                .extracting(e -> e.field())
                .contains("body.capturedAmount.currency");
    }

    @Test
    @DisplayName("a well-formed event passes")
    void acceptsAValidEvent() {
        RawEventValidator validator = new RawEventValidator(registry, properties, Json.mapper());

        ValidationOutcome outcome = validator.validate(new IngestRequest(
                null, "logistics", "ShipmentDelivered", "MER-0001", null, null, null, null, null, null,
                Json.readTree("""
                        {"shipmentId":"SHP-77","deliveryId":"DLV-77",
                         "deliveredAt":"2026-08-26T09:00:00Z","signedBy":"R. Sharma",
                         "proofType":"SIGNATURE","geo":{"lat":12.97,"lon":77.59}}
                        """)));

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.schemaName()).isEqualTo("shipment-delivered");
    }
}
