package com.laserpay.pdei.ingestion;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.config.IngestionConfiguration;
import com.laserpay.pdei.ingestion.config.IngestionProperties;
import com.laserpay.pdei.ingestion.metrics.IngestionMetrics;
import com.laserpay.pdei.ingestion.service.IngestionService;
import com.laserpay.pdei.ingestion.security.WebhookSignatureVerifier;
import com.laserpay.pdei.ingestion.validation.RawEventValidator;
import com.laserpay.pdei.ingestion.validation.SchemaRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The bean set a {@code @WebMvcTest} slice needs to exercise the real ingestion pipeline.
 *
 * <p>Deliberately imports the <em>real</em> {@link SchemaRegistry}, {@link RawEventValidator} and
 * {@link IngestionService}: the point of these tests is that a genuine JSON Schema violation is
 * caught by the genuine validator against the genuine schema files, which are on the test classpath
 * because the module POM copies {@code /schemas/events} into {@code target/classes}. Mocking the
 * validator would test only that the controller can call a mock.
 *
 * <p>Only the two infrastructure collaborators are mocked by the tests themselves - the Kafka
 * publisher and the idempotency store - because a unit test must not need a broker or a Redis.
 *
 * <p>The clock is fixed so that webhook timestamp tolerance and receipt times are deterministic.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
@Import({IngestionConfiguration.class, SchemaRegistry.class, RawEventValidator.class,
        IngestionService.class, IngestionMetrics.class, WebhookSignatureVerifier.class})
public class IngestionTestSupport {

    /** The instant every test sees as "now". */
    public static final Instant FIXED_NOW = Instant.parse("2026-08-26T10:15:30Z");

    /** Merchant used across the ingestion tests. */
    public static final String MERCHANT_ID = "MER-0001";

    @Bean
    @Primary
    Clocks testClock() {
        return Clocks.fixed(FIXED_NOW);
    }

    // Marked primary because the slice may also autoconfigure a registry; the assertions must read
    // the same one IngestionMetrics wrote to.
    @Bean
    @Primary
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
