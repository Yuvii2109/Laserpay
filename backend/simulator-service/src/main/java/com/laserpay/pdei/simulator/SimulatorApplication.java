package com.laserpay.pdei.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * simulator-service (platform contract 2: port 8088, Spring Boot web).
 *
 * <p>The synthetic financial world and the chaos engine. It generates a deterministic,
 * seed-reproducible world of merchants, transactions, evidence and disputes; emits it onto
 * {@code pdei.raw.events.v1} at a controlled rate; injects every {@code ChaosType} in the
 * platform contract; and replays topics from an offset or a timestamp.
 *
 * <p>This is the only service that deliberately breaks the others, which is exactly why the
 * platform's resilience claims are testable rather than aspirational.
 *
 * <p>The persistence layer and the domain engine arrive by autoconfiguration:
 * {@code platform-persistence} registers the entity and repository scan (including
 * {@code simulation_runs} and {@code chaos_injections}), {@code evidence-core} registers the
 * {@code ObjectStore}, the {@code EventPublisherPort} and the {@code AuditRecorder}.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
