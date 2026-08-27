package com.laserpay.pdei.orchestrator.config;

import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.orchestrator.model.CaseTimers;
import com.laserpay.pdei.orchestrator.workflow.DisputeCaseWorkflow;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal wiring for namespace {@code pdei} and task queue {@code pdei-dispute-cases}
 * (PLATFORM-CONTRACT section 10).
 *
 * <p>The Temporal Spring Boot starter builds the service stubs, the {@code WorkflowClient} and the
 * worker from {@code spring.temporal.*} in {@code application.yml}, and discovers
 * {@code DisputeCaseWorkflowImpl} and {@code CaseActivitiesImpl} through their
 * {@code @WorkflowImpl} / {@code @ActivityImpl} annotations. This class contributes the three
 * things the starter cannot infer:</p>
 * <ol>
 *   <li>the <b>data converter</b>, so payloads on the Temporal wire are serialised by exactly the
 *       same {@link Json#mapper()} that serialises Kafka events and Postgres JSONB - ISO-8601
 *       instants, no epoch numbers, unknown fields tolerated. Without this, a workflow input written
 *       by one build could fail to deserialise in the next;</li>
 *   <li><b>worker tuning</b> from {@link OrchestratorProperties.Worker};</li>
 *   <li>the standard {@link WorkflowOptions} used when a case is started, including the
 *       {@code WorkflowIdReusePolicy} that makes duplicate {@code DisputeCreated} deliveries
 *       harmless.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrchestratorProperties.class)
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    /** PLATFORM-CONTRACT section 2 and 10. */
    public static final String NAMESPACE = DisputeCaseWorkflow.NAMESPACE;
    /** PLATFORM-CONTRACT section 10. */
    public static final String TASK_QUEUE = DisputeCaseWorkflow.TASK_QUEUE;

    private final OrchestratorProperties properties;

    public TemporalConfig(OrchestratorProperties properties) {
        this.properties = properties;
    }

    /**
     * One JSON dialect across the whole platform. Temporal's default converter would use its own
     * ObjectMapper; ours is the shared {@link Json#mapper()}, so a {@code Money}, an {@code Instant}
     * or a {@code DisputeReasonCode} looks identical on the Temporal wire, on Kafka and in Postgres.
     */
    @Bean
    public DataConverter pdeiTemporalDataConverter() {
        return DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(Json.mapper()));
    }

    /** Namespace and converter for every client-side call (starts, signals, queries, describes). */
    @Bean
    public TemporalOptionsCustomizer<WorkflowClientOptions.Builder> pdeiWorkflowClientCustomizer(
            DataConverter dataConverter) {
        return builder -> builder
                .setNamespace(NAMESPACE)
                .setDataConverter(dataConverter)
                .setIdentity("pdei-case-orchestrator-service");
    }

    /** Worker pool sizes. Activities outnumber workflow tasks in this workload. */
    @Bean
    public TemporalOptionsCustomizer<WorkerOptions.Builder> pdeiWorkerCustomizer() {
        OrchestratorProperties.Worker worker = properties.getWorker();
        log.info("temporal worker on task queue {}: {} workflow task slots, {} activity slots",
                TASK_QUEUE, worker.getMaxConcurrentWorkflowTaskExecutors(),
                worker.getMaxConcurrentActivityExecutors());
        return builder -> builder
                .setMaxConcurrentWorkflowTaskExecutionSize(worker.getMaxConcurrentWorkflowTaskExecutors())
                .setMaxConcurrentActivityExecutionSize(worker.getMaxConcurrentActivityExecutors())
                .setMaxConcurrentLocalActivityExecutionSize(
                        worker.getMaxConcurrentLocalActivityExecutors());
    }

    /**
     * Sticky-cache size. A dispute case is mostly asleep, so keeping a good number of executions
     * cached avoids replaying history every time a signal or a timer wakes one up.
     */
    @Bean
    public TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> pdeiWorkerFactoryCustomizer() {
        return builder -> builder
                .setWorkflowCacheSize(properties.getWorker().getWorkflowCacheSize())
                .setMaxWorkflowThreadCount(properties.getWorker().getWorkflowCacheSize() * 2);
    }

    /** The timer set pinned into every workflow started by this process. */
    @Bean
    public CaseTimers pdeiCaseTimers() {
        CaseTimers timers = properties.toCaseTimers();
        log.info("case timers: missingEvidenceWait={} humanApprovalTimeout={} escalationTimeout={}"
                        + " followUpInterval={} followUpMax={} continueAsNewAt={} events",
                timers.missingEvidenceWait(), timers.humanApprovalTimeout(),
                timers.escalationTimeout(), timers.followUpInterval(), timers.followUpMaxDuration(),
                timers.continueAsNewHistoryThreshold());
        return timers;
    }

    /**
     * The options every case is started with.
     *
     * <p><b>{@code ALLOW_DUPLICATE_FAILED_ONLY} is the safety property that makes the Kafka listener
     * simple.</b> The workflow id is derived from the dispute id, so a redelivered
     * {@code DisputeCreated}:</p>
     * <ul>
     *   <li>while the case is running - is rejected with {@code WorkflowExecutionAlreadyStarted},
     *       which the listener swallows;</li>
     *   <li>after the case completed - is rejected the same way, so a replayed topic cannot re-open
     *       a closed case;</li>
     *   <li>after the case <em>failed</em> - is allowed, so a redelivery genuinely retries a case
     *       that crashed. That is the one duplicate worth acting on.</li>
     * </ul>
     */
    public WorkflowOptions workflowOptions(String workflowId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowIdReusePolicy(
                        io.temporal.api.enums.v1.WorkflowIdReusePolicy
                                .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowExecutionTimeout(properties.getWorkflowExecutionTimeout())
                .setWorkflowTaskTimeout(properties.getWorkflowTaskTimeout())
                .build();
    }
}
