package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.event.EventType;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import com.laserpay.pdei.common.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reproducibility guarantee, asserted rather than described.
 *
 * <p>Rule 11 says workloads are reproducible via deterministic seeds. That is only worth
 * anything if it holds at the byte level: a benchmark comparing two builds is meaningless if the
 * two runs saw slightly different data. So the central assertion here serialises every generated
 * envelope and compares the resulting bytes, which catches the failure modes a count-based check
 * would sail past - a {@code Map.of} with its per-JVM salted iteration order, an
 * {@code Instant.now()} that crept into a payload, a {@code UUID.randomUUID()} used for an id.
 */
class WorldGeneratorDeterminismTest {

    private static final long SEED = 4281L;
    private static final Instant START = Instant.parse("2026-01-05T06:00:00Z");

    private final WorldGenerator generator = new WorldGenerator();

    private static WorldSpec spec(long seed) {
        return new WorldSpec(seed, 2, 25, 14, 2_500, FailureMix.realistic(), null, "INR",
                START, null, 0L, 0);
    }

    @Test
    @DisplayName("the same seed produces a byte-identical event sequence")
    void sameSeedProducesByteIdenticalEvents() {
        GeneratedWorld first = generator.generate(spec(SEED));
        GeneratedWorld second = generator.generate(spec(SEED));

        assertThat(second.eventCount()).isEqualTo(first.eventCount());
        assertThat(serialise(first)).isEqualTo(serialise(second));
    }

    @Test
    @DisplayName("identifiers, timestamps and payload bytes all repeat exactly")
    void identifiersAndTimestampsRepeat() {
        GeneratedWorld first = generator.generate(spec(SEED));
        GeneratedWorld second = generator.generate(spec(SEED));

        assertThat(second.merchantIds()).isEqualTo(first.merchantIds());
        assertThat(second.transactionIds()).isEqualTo(first.transactionIds());
        assertThat(second.evidenceIds()).isEqualTo(first.evidenceIds());
        assertThat(second.disputedTransactionIds()).isEqualTo(first.disputedTransactionIds());
        assertThat(second.counts()).isEqualTo(first.counts());
        assertThat(second.grossValue()).isEqualTo(first.grossValue());

        for (int i = 0; i < first.eventCount(); i++) {
            SimEvent left = first.events().get(i);
            SimEvent right = second.events().get(i);
            assertThat(right.envelope().rawEventId()).isEqualTo(left.envelope().rawEventId());
            assertThat(right.envelope().idempotencyKey()).isEqualTo(left.envelope().idempotencyKey());
            assertThat(right.occurredAt()).isEqualTo(left.occurredAt());
            assertThat(right.observedAt()).isEqualTo(left.observedAt());
        }
    }

    @Test
    @DisplayName("synthetic artifact bytes and their hashes are reproducible")
    void artifactBytesAreReproducible() {
        List<SyntheticArtifact> first = generator.generate(spec(SEED)).artifacts();
        List<SyntheticArtifact> second = generator.generate(spec(SEED)).artifacts();

        assertThat(first).isNotEmpty();
        assertThat(second).hasSameSizeAs(first);
        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).sha256()).isEqualTo(first.get(i).sha256());
            assertThat(second.get(i).objectKey()).isEqualTo(first.get(i).objectKey());
            assertThat(second.get(i).content()).isEqualTo(first.get(i).content());
        }
    }

    @Test
    @DisplayName("a different seed produces a different world")
    void differentSeedProducesDifferentWorld() {
        GeneratedWorld first = generator.generate(spec(SEED));
        GeneratedWorld other = generator.generate(spec(SEED + 1));

        assertThat(other.merchantIds()).isNotEqualTo(first.merchantIds());
        assertThat(serialise(other)).isNotEqualTo(serialise(first));
    }

    @Test
    @DisplayName("moving startAt shifts every timestamp and nothing else")
    void startAtShiftsTheWholeWorld() {
        GeneratedWorld base = generator.generate(spec(SEED));
        GeneratedWorld shifted = generator.generate(spec(SEED).withStartAt(START.plusSeconds(86_400)));

        assertThat(shifted.eventCount()).isEqualTo(base.eventCount());
        assertThat(shifted.transactionIds()).isEqualTo(base.transactionIds());
        for (int i = 0; i < base.eventCount(); i++) {
            assertThat(shifted.events().get(i).occurredAt())
                    .isEqualTo(base.events().get(i).occurredAt().plusSeconds(86_400));
        }
    }

    @Test
    @DisplayName("generates the full domain: orders, payments, shipments, evidence and disputes")
    void generatesTheWholeDomain() {
        GeneratedWorld world = generator.generate(spec(SEED));

        assertThat(world.merchantIds()).hasSize(2).allMatch(id -> id.startsWith("MER-"));
        assertThat(world.transactionIds()).hasSize(25).allMatch(id -> id.startsWith("TX-"));
        assertThat(world.evidenceIds()).isNotEmpty().allMatch(id -> id.startsWith("EV-"));
        assertThat(world.count(GeneratedWorld.COUNT_SHIPMENTS)).isPositive();
        assertThat(world.count(GeneratedWorld.COUNT_COMMUNICATIONS)).isPositive();
        assertThat(world.grossValue().currency()).isEqualTo("INR");
        assertThat(world.grossValue().amountMinor()).isPositive();

        List<EventType> types = world.events().stream().map(SimEvent::canonicalType).distinct().toList();
        assertThat(types).contains(EventType.OrderCreated, EventType.PaymentCaptured,
                EventType.ShipmentDispatched, EventType.EvidenceAdded);
    }

    @Test
    @DisplayName("every envelope carries the mandatory partition key and canonical type hint")
    void envelopesAreWellFormed() {
        GeneratedWorld world = generator.generate(spec(SEED));

        for (SimEvent event : world.events()) {
            RawEventEnvelope envelope = event.envelope();
            assertThat(envelope.merchantId()).startsWith("MER-");
            assertThat(envelope.partitionKey())
                    .isEqualTo(envelope.merchantId() + ":" + envelope.idempotencyKey());
            assertThat(envelope.header("pdei-event-type")).isEqualTo(event.canonicalType().name());
            assertThat(envelope.header("pdei-merchant-id")).isEqualTo(envelope.merchantId());
            // Raw events speak the source system's vocabulary, never the canonical enum name.
            assertThat(envelope.sourceEventType()).doesNotContain(event.canonicalType().name());
        }
    }

    @Test
    @DisplayName("events are ordered by observation time, so late arrivals really are late")
    void streamIsOrderedByObservation() {
        GeneratedWorld world = generator.generate(
                new WorldSpec(SEED, 1, 60, 14, 0, FailureMix.clean().withLateEvents(3_000),
                        null, "INR", START, null, 0L, 0));

        assertThat(world.events().stream().anyMatch(SimEvent::isLate)).isTrue();
        // Occurrence order is deliberately NOT the emission order for a late event.
        boolean someEventOvertakenByAnOlderOne = false;
        for (int i = 1; i < world.eventCount(); i++) {
            if (world.events().get(i).occurredAt().isBefore(world.events().get(i - 1).occurredAt())) {
                someEventOvertakenByAnOlderOne = true;
                break;
            }
        }
        assertThat(someEventOvertakenByAnOlderOne).isTrue();
    }

    @Test
    @DisplayName("a clean profile emits no duplicates and drops nothing")
    void cleanProfileIsClean() {
        GeneratedWorld world = generator.generate(
                new WorldSpec(SEED, 1, 15, 10, 0, FailureMix.clean(), null, "INR", START, null, 0L, 0));

        assertThat(world.count(GeneratedWorld.COUNT_DUPLICATE_EVENTS)).isZero();
        assertThat(world.count(GeneratedWorld.COUNT_DROPPED_EVENTS)).isZero();
        assertThat(world.count(GeneratedWorld.COUNT_DISPUTES)).isZero();
    }

    /** Full byte-level serialisation of the emitted stream - the strongest determinism check. */
    private static byte[] serialise(GeneratedWorld world) {
        StringBuilder sb = new StringBuilder(world.eventCount() * 512);
        for (SimEvent event : world.events()) {
            sb.append(event.sequence()).append('|')
                    .append(event.canonicalType().name()).append('|')
                    .append(event.occurredAt()).append('|')
                    .append(event.observedAt()).append('|')
                    .append(Json.write(event.envelope()))
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
