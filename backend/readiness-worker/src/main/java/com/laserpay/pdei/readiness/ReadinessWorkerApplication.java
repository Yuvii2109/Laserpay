package com.laserpay.pdei.readiness;

import com.laserpay.pdei.readiness.config.ReadinessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PDEI readiness worker (docs/PLATFORM-CONTRACT.md section 2, port 8084).
 *
 * <p>Readiness is a <em>continuously maintained</em> property, not a report someone runs. This
 * process is the thing that keeps it current:</p>
 *
 * <ol>
 *   <li>consume {@code pdei.evidence.events.v1} and {@code pdei.canonical.events.v1};</li>
 *   <li>debounce per transaction so an event burst causes one computation, not twenty;</li>
 *   <li>run the deterministic {@code evidence-core} {@code ReadinessEngine};</li>
 *   <li>persist {@code readiness_snapshots} + {@code readiness_gaps}, cache
 *       {@code pdei:readiness:{transactionId}}, publish {@code ReadinessRecomputed} /
 *       {@code ReadinessGapDetected} to {@code pdei.readiness.events.v1};</li>
 *   <li>sweep evidence expiry nightly and materialise the at-risk feed periodically.</li>
 * </ol>
 *
 * <p>The worker computes and records; it never invents financial facts and never calls a model.
 * Everything it writes is reproducible from the same inputs (non-negotiable rules 1, 2 and 6).</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ReadinessProperties.class)
public class ReadinessWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadinessWorkerApplication.class, args);
    }
}
