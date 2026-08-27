package com.laserpay.pdei.persistence.config;

import com.laserpay.pdei.persistence.PdeiSchema;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Makes {@code platform-persistence} a drop-in dependency: a service module adds the artifact
 * and immediately has every PDEI entity mapped, every repository injectable and the {@code pdei}
 * schema migrated. No copy-pasted {@code @EntityScan} in nine services.
 *
 * <p>Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Ordering: it must be processed before Hibernate and Spring Data JPA autoconfiguration, so
 * the entity packages are known when the {@code EntityManagerFactory} is built.
 *
 * <p>Scanning is anchored at {@link PdeiSchema#BASE_PACKAGE} on purpose — see the constant's
 * javadoc: declaring {@code @EnableJpaRepositories} here disables Spring Boot's own repository
 * scan, so this one has to cover service-local repositories too.
 *
 * <p>Services still need the datasource itself, e.g.
 * <pre>
 * spring.datasource.url=${PDEI_POSTGRES_URL}
 * spring.datasource.username=${PDEI_POSTGRES_USER}
 * spring.datasource.password=${PDEI_POSTGRES_PASSWORD}
 * </pre>
 */
@AutoConfiguration(before = {HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
@ConditionalOnClass({EntityManagerFactory.class, JpaRepository.class, DataSource.class})
@EntityScan(basePackages = PdeiSchema.BASE_PACKAGE)
@EnableJpaRepositories(basePackages = PdeiSchema.BASE_PACKAGE)
public class PersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PersistenceAutoConfiguration.class);

    public PersistenceAutoConfiguration() {
        log.debug("PDEI persistence autoconfiguration active: entities and repositories under {}, schema '{}'",
                PdeiSchema.BASE_PACKAGE, PdeiSchema.NAME);
    }

    /**
     * Flyway defaults for the schema this module owns.
     *
     * <p>Only fills in what the application has not configured, so a service can still override
     * {@code spring.flyway.*}. {@code createSchemas} is forced on because the migrations live in
     * a non-default schema that must exist before V1 runs.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class PdeiFlywayDefaults {

        @Bean
        FlywayConfigurationCustomizer pdeiFlywayConfigurationCustomizer() {
            return configuration -> {
                if (configuration.getSchemas() == null || configuration.getSchemas().length == 0) {
                    configuration.schemas(PdeiSchema.NAME);
                }
                if (configuration.getDefaultSchema() == null) {
                    configuration.defaultSchema(PdeiSchema.NAME);
                }
                configuration.createSchemas(true);
                log.debug("Flyway configured for schema '{}' with locations {}",
                        PdeiSchema.NAME, (Object) configuration.getLocations());
            };
        }
    }
}
