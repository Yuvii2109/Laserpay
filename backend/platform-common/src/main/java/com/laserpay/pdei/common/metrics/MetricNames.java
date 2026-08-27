package com.laserpay.pdei.common.metrics;

import java.util.List;

/**
 * Metric and tag names, matching PLATFORM-CONTRACT section 13 exactly.
 *
 * <p>Every Java service registers its meters through these constants and the Python service uses
 * the same strings, so a Grafana panel or a Prometheus alert rule written once works against the
 * whole platform. Micrometer's Prometheus registry converts a name like
 * {@code pdei_events_processed_total} to itself (it is already snake_case with the required
 * {@code _total} suffix), so what is declared here is what is scraped.
 *
 * <p>Tag <em>cardinality</em> matters: tag values must be bounded vocabularies (enum names, service
 * names, topics). Never tag with a merchantId-per-transaction, an evidenceId or a correlationId.
 * The one merchant-tagged metric, {@link #READINESS_SCORE}, is a gauge over a bounded merchant set
 * in this workload.
 */
public final class MetricNames {

    // --- event pipeline -----------------------------------------------------------------------
    /** Counter, tags: source, type. Raw events accepted by ingestion/simulator. */
    public static final String EVENTS_INGESTED_TOTAL = "pdei_events_ingested_total";
    /** Counter, tags: service, type, outcome. */
    public static final String EVENTS_PROCESSED_TOTAL = "pdei_events_processed_total";
    /** Counter, tags: service. Increments when idempotency suppresses a redelivery. */
    public static final String EVENTS_DUPLICATE_TOTAL = "pdei_events_duplicate_total";
    /** Timer, tags: service, type. */
    public static final String EVENT_PROCESSING_LATENCY_SECONDS = "pdei_event_processing_latency_seconds";
    /** Gauge, tags: group, topic. */
    public static final String KAFKA_CONSUMER_LAG = "pdei_kafka_consumer_lag";

    // --- readiness / evidence ------------------------------------------------------------------
    /** Timer. Wall time of one deterministic readiness computation. */
    public static final String READINESS_COMPUTATION_SECONDS = "pdei_readiness_computation_seconds";
    /** Gauge, tags: merchant. */
    public static final String READINESS_SCORE = "pdei_readiness_score";
    /** Gauge, tags: type, status. */
    public static final String EVIDENCE_TOTAL = "pdei_evidence_total";
    /** Timer. Wall time to assemble a representment case. */
    public static final String CASE_ASSEMBLY_SECONDS = "pdei_case_assembly_seconds";

    // --- AI ------------------------------------------------------------------------------------
    /** Counter, tags: provider, outcome. */
    public static final String AI_REQUESTS_TOTAL = "pdei_ai_requests_total";
    /** Counter, tags: decision. Admission control admit/deny split. */
    public static final String AI_ADMISSION_TOTAL = "pdei_ai_admission_total";
    /** Timer, tags: provider. */
    public static final String AI_LATENCY_SECONDS = "pdei_ai_latency_seconds";
    /** Counter. Model claims rejected by the validator - the honesty metric. */
    public static final String AI_UNSUPPORTED_CLAIMS_TOTAL = "pdei_ai_unsupported_claims_total";

    // --- policy / workflow / chaos ---------------------------------------------------------------
    /** Counter, tags: decision (SafetyDecision name). */
    public static final String POLICY_GATE_TOTAL = "pdei_policy_gate_total";
    /** Counter, tags: workflow. */
    public static final String WORKFLOW_FAILURES_TOTAL = "pdei_workflow_failures_total";
    /** Counter, tags: type (ChaosType name). */
    public static final String CHAOS_INJECTIONS_TOTAL = "pdei_chaos_injections_total";

    public static final List<String> ALL = List.of(
            EVENTS_INGESTED_TOTAL,
            EVENTS_PROCESSED_TOTAL,
            EVENTS_DUPLICATE_TOTAL,
            EVENT_PROCESSING_LATENCY_SECONDS,
            KAFKA_CONSUMER_LAG,
            READINESS_COMPUTATION_SECONDS,
            READINESS_SCORE,
            EVIDENCE_TOTAL,
            CASE_ASSEMBLY_SECONDS,
            AI_REQUESTS_TOTAL,
            AI_ADMISSION_TOTAL,
            AI_LATENCY_SECONDS,
            AI_UNSUPPORTED_CLAIMS_TOTAL,
            POLICY_GATE_TOTAL,
            WORKFLOW_FAILURES_TOTAL,
            CHAOS_INJECTIONS_TOTAL);

    private MetricNames() {
    }

    /** Tag keys used with the metrics above. */
    public static final class Tag {

        public static final String SOURCE = "source";
        public static final String TYPE = "type";
        public static final String SERVICE = "service";
        public static final String OUTCOME = "outcome";
        public static final String GROUP = "group";
        public static final String TOPIC = "topic";
        public static final String MERCHANT = "merchant";
        public static final String STATUS = "status";
        public static final String PROVIDER = "provider";
        public static final String DECISION = "decision";
        public static final String WORKFLOW = "workflow";

        private Tag() {
        }
    }

    /** Bounded vocabulary for the {@code outcome} tag on processing counters. */
    public static final class Outcome {

        public static final String SUCCESS = "success";
        public static final String DUPLICATE = "duplicate";
        public static final String FAILURE = "failure";
        public static final String DEAD_LETTERED = "dead_lettered";
        public static final String SKIPPED = "skipped";

        private Outcome() {
        }
    }
}
