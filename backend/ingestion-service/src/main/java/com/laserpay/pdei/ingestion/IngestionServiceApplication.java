package com.laserpay.pdei.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * PDEI ingestion-service (port 8081).
 *
 * <p>The only write door into the platform for events originating outside it. Nothing else may
 * publish to {@code pdei.raw.events.v1} except simulator-service, which is a synthetic source of
 * the same shape (PLATFORM-CONTRACT section 4).
 *
 * <p>The pipeline for every submitted event is fixed and deliberately short:
 *
 * <ol>
 *   <li><strong>validate</strong> - the submission envelope against {@code raw-event.schema.json}
 *       and the body against the registered schema for its event type
 *       ({@code com.laserpay.pdei.ingestion.validation});</li>
 *   <li><strong>deduplicate</strong> - Redis {@code SETNX pdei:idem:{eventId}} with a 7 day TTL,
 *       falling back to {@code processed_events} in Postgres when Redis is unavailable
 *       ({@code com.laserpay.pdei.ingestion.dedupe});</li>
 *   <li><strong>publish</strong> - to {@code pdei.raw.events.v1}, keyed
 *       {@code merchantId + ":" + aggregateId}, with the contract's Kafka headers, dead-lettering
 *       to {@code pdei.dlq.v1} on failure ({@code com.laserpay.pdei.ingestion.publisher}).</li>
 * </ol>
 *
 * <p>What this service deliberately does <em>not</em> do: interpret the body. Source vocabulary is
 * translated to the canonical event model by normalization-worker and nowhere else. Ingestion only
 * proves that the payload is well-formed enough to be worth replaying, and that it has not been
 * seen before.
 *
 * <p>No AI code lives here, and none ever will (PLATFORM-CONTRACT section 17, rules 2 and 14).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
