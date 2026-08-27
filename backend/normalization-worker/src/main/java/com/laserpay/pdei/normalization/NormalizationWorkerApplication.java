package com.laserpay.pdei.normalization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * normalization-worker (PLATFORM-CONTRACT section 2, port 8082).
 *
 * <p>Consumes {@code pdei.raw.events.v1}, translates each source system's shape into a
 * {@code CanonicalEvent} through a {@code SourceAdapter}, and publishes to
 * {@code pdei.canonical.events.v1}. Anything it cannot map goes to {@code pdei.dlq.v1}.
 *
 * <p>This is a worker, not a service: the HTTP port exists only for {@code /actuator/health} and
 * {@code /actuator/prometheus}. It exposes no application API, owns no domain state, and writes to
 * exactly one table - {@code processed_events}, for idempotency.
 *
 * <p>Entities and repositories arrive through {@code platform-persistence}'s autoconfiguration; the
 * domain engine ({@code evidence-core}) is deliberately absent, because translation must not be able
 * to reach financial state.
 */
@SpringBootApplication
@EnableKafka
@EnableTransactionManagement
public class NormalizationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NormalizationWorkerApplication.class, args);
    }
}
