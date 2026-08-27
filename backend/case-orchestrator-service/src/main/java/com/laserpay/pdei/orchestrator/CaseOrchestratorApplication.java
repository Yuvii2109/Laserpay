package com.laserpay.pdei.orchestrator;

import com.laserpay.pdei.orchestrator.config.OrchestratorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * PDEI case-orchestrator-service, port 8085 (PLATFORM-CONTRACT section 2).
 *
 * <p>Three things run in this process:</p>
 * <ol>
 *   <li>a <b>Temporal worker</b> on namespace {@code pdei}, task queue {@code pdei-dispute-cases},
 *       hosting {@code DisputeCaseWorkflowImpl} and {@code CaseActivitiesImpl};</li>
 *   <li>a <b>Kafka consumer</b> on {@code pdei.dispute.events.v1} that starts and signals cases;</li>
 *   <li>a small <b>internal HTTP API</b> under {@code /orchestrator/v1} plus actuator, so the
 *       api-gateway can approve, reject, submit, query and describe a case without a Temporal
 *       client of its own.</li>
 * </ol>
 *
 * <p>The domain engine ({@code evidence-core}) and the persistence layer
 * ({@code platform-persistence}) register themselves through their auto-configurations, so this
 * class stays a plain entry point.</p>
 */
@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties(OrchestratorProperties.class)
public class CaseOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseOrchestratorApplication.class, args);
    }
}
