package com.laserpay.pdei.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for the persistence integration tests: a real PostgreSQL 16 in Docker, migrated by
 * the real Flyway scripts shipped in this module. Nothing is mocked — the whole point is to
 * assert the behaviour of the actual schema (triggers, partial unique indexes, ON CONFLICT
 * semantics, tsvector maintenance), which an in-memory database could not reproduce.
 *
 * <p>The container is shared by every subclass (one static instance), so the suite pays the
 * startup cost once. Subclasses call {@link #truncateAll()} for isolation.
 */
@SpringBootTest(classes = PersistenceTestApplication.class)
public abstract class AbstractPostgresIntegrationTest {

    /**
     * Singleton-container pattern: deliberately <strong>not</strong> annotated {@code @Container},
     * and the class is deliberately <strong>not</strong> annotated {@code @Testcontainers}.
     *
     * <p>The JUnit Testcontainers extension stops an annotated static container at the end of
     * <em>every</em> test class. Since this base class is shared, the second subclass would start a
     * fresh container on a new random port while Spring reused its <em>cached</em> application
     * context — which still pointed at the now-dead first container. The symptom was a 30s Hikari
     * timeout ("Connection is not available, request timed out after 30001ms") on every test after
     * the first class, while the first class passed cleanly.
     *
     * <p>Started lazily in {@link #datasourceProperties(DynamicPropertyRegistry)} instead, reused by
     * every subclass for the lifetime of the JVM, and reaped by Ryuk or JVM exit. Lazy start also
     * keeps {@link #dockerAvailable()} meaningful: a Docker-less box never touches Docker at all.
     */
    @SuppressWarnings("resource") // intentionally outlives every test class; see above
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pdei")
            .withUsername("pdei")
            .withPassword("pdei");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Guard used by {@code @EnabledIf} on the concrete tests so a Docker-less developer box skips
     * them instead of failing.
     *
     * <p><strong>In CI this deliberately never skips.</strong> A silent skip there would let the
     * "Backend (integration)" job report success having executed nothing — the integration suite
     * would be permanently, invisibly green. GitHub Actions sets {@code CI=true}, so on a runner
     * without a usable Docker daemon these tests fail loudly, which is the correct outcome: the
     * job exists precisely to prove the schema behaves against a real PostgreSQL.
     */
    public static boolean dockerAvailable() {
        if (Boolean.parseBoolean(System.getenv("CI"))) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Runs only while building a context for an *enabled* test class, so a Docker-less box
        // never starts anything. Idempotent: the first subclass starts it, the rest reuse it.
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
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
