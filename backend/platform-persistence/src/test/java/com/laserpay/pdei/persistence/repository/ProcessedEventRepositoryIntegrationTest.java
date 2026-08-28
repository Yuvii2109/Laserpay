package com.laserpay.pdei.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laserpay.pdei.persistence.AbstractPostgresIntegrationTest;
import com.laserpay.pdei.persistence.entity.ProcessedEventId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The idempotency primitive every Kafka consumer in PDEI depends on (rule 9: all consumers
 * tolerate duplicates).
 */
@EnabledIf(value = "com.laserpay.pdei.persistence.AbstractPostgresIntegrationTest#dockerAvailable",
        disabledReason = "Docker is not available; skipping Testcontainers integration test")
class ProcessedEventRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String GROUP_A = "pdei-readiness-worker";
    private static final String GROUP_B = "pdei-audit-service";

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Test
    @DisplayName("migrations V1..V10 are applied to the pdei schema")
    void migrationsApplied() {
        // `version IS NOT NULL` filters out Flyway 10's "<< Flyway Schema Creation >>" marker row,
        // which it records with a NULL version and type SCHEMA when it creates the pdei schema.
        // Counting raw rows here asserted 10 and got 11 - all ten migrations had in fact applied.
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pdei.flyway_schema_history WHERE success AND version IS NOT NULL",
                Integer.class);
        assertThat(applied).isEqualTo(10);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'pdei' ORDER BY tablename",
                String.class);
        assertThat(tables).contains("merchants", "customers", "processed_events", "transactions",
                "evidence", "evidence_versions", "policies", "disputes", "dispute_cases",
                "readiness_snapshots", "readiness_gaps", "investigations", "audit_events",
                "simulation_runs", "chaos_injections");
    }

    @Test
    @DisplayName("markProcessed claims an event exactly once per consumer group")
    void markProcessedIsIdempotent() {
        String eventId = UUID.randomUUID().toString();

        assertThat(processedEvents.markProcessed(eventId, GROUP_A))
                .as("first sighting must be claimed")
                .isTrue();

        assertThat(processedEvents.markProcessed(eventId, GROUP_A))
                .as("redelivery of the same event to the same group is a duplicate")
                .isFalse();
        assertThat(processedEvents.markProcessed(eventId, GROUP_A)).isFalse();

        assertThat(processedEvents.markProcessed(eventId, GROUP_B))
                .as("a different consumer group must still get to process the event")
                .isTrue();

        assertThat(processedEvents.count()).isEqualTo(2);
        assertThat(processedEvents.findConsumerGroupsFor(eventId))
                .containsExactlyInAnyOrder(GROUP_A, GROUP_B);
        assertThat(processedEvents.wasProcessed(eventId, GROUP_A)).isTrue();
        assertThat(processedEvents.wasProcessed(eventId, "pdei-simulator-service")).isFalse();
    }

    @Test
    @DisplayName("the first insert wins and the row it wrote is readable")
    void markProcessedPersistsTheMarker() {
        String eventId = UUID.randomUUID().toString();
        Instant before = Instant.now().minusSeconds(1);

        assertThat(processedEvents.markProcessed(eventId, GROUP_A)).isTrue();

        var stored = processedEvents.findById(new ProcessedEventId(eventId, GROUP_A));
        assertThat(stored).isPresent();
        assertThat(stored.get().getEventId()).isEqualTo(eventId);
        assertThat(stored.get().getConsumerGroup()).isEqualTo(GROUP_A);
        assertThat(stored.get().getProcessedAt()).isAfterOrEqualTo(before);
        assertThat(processedEvents.countByIdConsumerGroup(GROUP_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent consumers racing on one event: exactly one claim succeeds")
    void markProcessedIsAtomicUnderConcurrency() throws Exception {
        String eventId = UUID.randomUUID().toString();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> attempts = IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> processedEvents.markProcessed(eventId, GROUP_A))
                    .toList();

            long winners = pool.invokeAll(attempts).stream()
                    .map(ProcessedEventRepositoryIntegrationTest::get)
                    .filter(Boolean::booleanValue)
                    .count();

            assertThat(winners)
                    .as("INSERT ... ON CONFLICT DO NOTHING must let exactly one thread through")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("retention pruning removes markers older than the dedupe window")
    void deleteProcessedBeforePrunesOldMarkers() {
        String oldEvent = UUID.randomUUID().toString();
        String freshEvent = UUID.randomUUID().toString();

        assertThat(processedEvents.markProcessed(oldEvent, GROUP_A)).isTrue();
        assertThat(processedEvents.markProcessed(freshEvent, GROUP_A)).isTrue();
        jdbcTemplate.update("UPDATE pdei.processed_events SET processed_at = ? WHERE event_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), oldEvent);

        int pruned = processedEvents.deleteProcessedBefore(Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(pruned).isEqualTo(1);
        assertThat(processedEvents.wasProcessed(oldEvent, GROUP_A)).isFalse();
        assertThat(processedEvents.wasProcessed(freshEvent, GROUP_A)).isTrue();
        assertThat(processedEvents.markProcessed(oldEvent, GROUP_A))
                .as("a pruned marker is claimable again; the dedupe window is finite by design")
                .isTrue();
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException("concurrent markProcessed attempt failed", ex);
        }
    }
}
