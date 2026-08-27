package com.laserpay.pdei.persistence;

/**
 * Coordinates of the PDEI relational schema (docs/PLATFORM-CONTRACT.md section 5).
 *
 * <p>The schema name is a compile-time constant so it can be used directly in
 * {@code @Table(schema = PdeiSchema.NAME)} on every entity. Entities therefore resolve
 * correctly regardless of the connection's {@code search_path}, and services need no
 * {@code hibernate.default_schema} property.
 */
public final class PdeiSchema {

    /** Postgres schema owned by this module and migrated by its Flyway scripts. */
    public static final String NAME = "pdei";

    /** Classpath location of the Flyway migrations shipped in this jar. */
    public static final String MIGRATION_LOCATION = "classpath:db/migration";

    /**
     * Root Java package of the whole platform. Entity and repository scanning is anchored here
     * rather than at {@link #ENTITY_PACKAGE} so that a service module declaring its own entities
     * or repositories under {@code com.laserpay.pdei.<service>} keeps working: our
     * {@code @EnableJpaRepositories} makes Spring Boot's own repository autoconfiguration back
     * off, so it must cover everything, not just this module.
     */
    public static final String BASE_PACKAGE = "com.laserpay.pdei";

    /** Java package containing the JPA entities (consumed by {@code @EntityScan}). */
    public static final String ENTITY_PACKAGE = "com.laserpay.pdei.persistence.entity";

    /** Java package containing the Spring Data repositories. */
    public static final String REPOSITORY_PACKAGE = "com.laserpay.pdei.persistence.repository";

    private PdeiSchema() {
    }
}
