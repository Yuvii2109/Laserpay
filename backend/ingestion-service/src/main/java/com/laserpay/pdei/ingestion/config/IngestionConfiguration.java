package com.laserpay.pdei.ingestion.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.laserpay.pdei.common.time.Clocks;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans that the rest of the service assumes exist.
 *
 * <p>Two things only, both about keeping this service's behaviour identical to every other PDEI
 * module's.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionConfiguration {

    /**
     * The only source of "now" in this service (docs/SHARED-LIBRARY-API.md section 1.9).
     *
     * <p>Nothing calls {@code Instant.now()} directly: receipt timestamps, webhook replay windows
     * and idempotency TTLs all depend on it, and they must be pinnable in tests. Always UTC, always
     * {@link java.time.Instant}; {@code LocalDateTime} appears nowhere in this platform.
     */
    @Bean
    public Clocks clocks() {
        return Clocks.system();
    }

    /**
     * Aligns Spring MVC's Jackson configuration with
     * {@code com.laserpay.pdei.common.json.Json#mapper()}.
     *
     * <p>A customizer rather than a replacement {@code ObjectMapper} bean: Boot's builder still
     * discovers and registers the modules on the classpath (JSR-310 among them), and only the four
     * settings that are part of the platform's wire contract are pinned here.
     *
     * <p>Why each one matters:
     * <ul>
     *   <li><strong>No timestamps for dates</strong> - every instant on the wire is ISO-8601 UTC.
     *       An epoch number would be read differently by the Python and TypeScript sides.</li>
     *   <li><strong>{@code NON_NULL} inclusion</strong> - an absent optional field must be absent,
     *       not {@code null}. The JSON Schemas type optional fields as their real type, so a
     *       serialised {@code null} would fail validation of a perfectly valid submission.</li>
     *   <li><strong>Tolerate unknown properties</strong> - a newer adapter must never be broken by
     *       an older ingestion build.</li>
     * </ul>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer pdeiJacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
