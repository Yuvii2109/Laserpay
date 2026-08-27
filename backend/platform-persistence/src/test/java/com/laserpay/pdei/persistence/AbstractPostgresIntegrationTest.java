package com.laserpay.pdei.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for the persistence integration tests: a real PostgreSQL 16 in Docker, migrated by
 * the real Flyway scripts shipped in this module. Nothing is mocked — the whole point is to
 * assert the behaviour of the actual schema (triggers, partial unique indexes, ON CONFLICT
 * semantics, tsvector maintenance), which an in-memory database could not reproduce.
 *
 * <p>The container is shared by every subclass (one static instance), so the suite pays the
 * startup cost once. Subclasses call {@link #truncateAll()} for isolation.
 */
@Testcontainers
@SpringBootTest(classes = PersistenceTestApplication.class)
public abstract class AbstractPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers stops the container via its JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pdei")
            .withUsername("pdei")
            .withPassword("pdei");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Guard used by {@code @EnabledIf} on the concrete tests so a Docker-less box skips them. */
    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void resetSchema() {
        truncateAll();
    }

    /**
     * Empties every PDEI table while keeping the migrated structure.
     *
     * <p>TRUNCATE, not DELETE: the append-only tables ({@code evidence_versions},
     * {@code audit_events}) carry row-level triggers that reject DELETE, and TRUNCATE bypasses
     * row triggers by design. {@code flyway_schema_history} is preserved so migrations are not
     * re-applied.
     */
    protected void truncateAll() {
        jdbcTemplate.execute("""
                DO $$
                DECLARE tables text;
                BEGIN
                    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
                      INTO tables
                      FROM pg_tables
                     WHERE schemaname = 'pdei' AND tablename <> 'flyway_schema_history';
                    IF tables IS NOT NULL THEN
                        EXECUTE 'TRUNCATE TABLE ' || tables || ' RESTART IDENTITY CASCADE';
                    END IF;
                END $$;
                """);
    }
}
