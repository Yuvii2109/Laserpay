package com.laserpay.pdei.common.kafka;

import java.util.List;

/**
 * Kafka consumer group ids (PLATFORM-CONTRACT section 4): {@code pdei-<service-name>}.
 *
 * <p>One group per service, never per topic: a service reading three topics uses one group so its
 * offsets, lag metric ({@code pdei_kafka_consumer_lag{group,topic}}) and replay bookmarks
 * ({@code pdei:stream:offsets:{consumerGroup}}) stay coherent.
 *
 * <p>The group id is also the second half of the Postgres idempotency key
 * ({@code processed_events(event_id, consumer_group)}), so changing a value here re-processes
 * history for that service. Treat these strings as data, not as cosmetics.
 */
public final class ConsumerGroups {

    private static final String PREFIX = "pdei-";

    public static final String PDEI_API_GATEWAY_SERVICE = PREFIX + "api-gateway-service";
    public static final String PDEI_INGESTION_SERVICE = PREFIX + "ingestion-service";
    public static final String PDEI_NORMALIZATION_WORKER = PREFIX + "normalization-worker";
    public static final String PDEI_STATE_BUILDER_WORKER = PREFIX + "state-builder-worker";
    public static final String PDEI_READINESS_WORKER = PREFIX + "readiness-worker";
    public static final String PDEI_CASE_ORCHESTRATOR_SERVICE = PREFIX + "case-orchestrator-service";
    public static final String PDEI_DOCUMENT_PROCESSOR_SERVICE = PREFIX + "document-processor-service";
    public static final String PDEI_AUDIT_SERVICE = PREFIX + "audit-service";
    public static final String PDEI_SIMULATOR_SERVICE = PREFIX + "simulator-service";

    /** Every consumer group, in service-registry order. */
    public static final List<String> ALL = List.of(
            PDEI_API_GATEWAY_SERVICE,
            PDEI_INGESTION_SERVICE,
            PDEI_NORMALIZATION_WORKER,
            PDEI_STATE_BUILDER_WORKER,
            PDEI_READINESS_WORKER,
            PDEI_CASE_ORCHESTRATOR_SERVICE,
            PDEI_DOCUMENT_PROCESSOR_SERVICE,
            PDEI_AUDIT_SERVICE,
            PDEI_SIMULATOR_SERVICE);

    private ConsumerGroups() {
    }

    /**
     * Group id for a module name, e.g. {@code forService("readiness-worker")} yields
     * {@code pdei-readiness-worker}. Use the constants where one exists; this exists for
     * generated/derived contexts such as replay tooling.
     */
    public static String forService(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) {
            throw new IllegalArgumentException("moduleName must not be blank");
        }
        return moduleName.startsWith(PREFIX) ? moduleName : PREFIX + moduleName;
    }
}
