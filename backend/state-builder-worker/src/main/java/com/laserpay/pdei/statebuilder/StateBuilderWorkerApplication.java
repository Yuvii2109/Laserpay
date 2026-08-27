package com.laserpay.pdei.statebuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * state-builder-worker (PLATFORM-CONTRACT section 2, port 8083).
 *
 * <p>Consumes {@code pdei.canonical.events.v1} and does three things with every event: maintains the
 * financial projections in PostgreSQL, derives evidence from lifecycle facts through
 * {@code evidence-core}, and forwards evidence and dispute events onto the topics their consumers
 * actually read.
 *
 * <p>This is where "evidence is captured at the moment the fact happens, not hunted for 45 days
 * later" becomes code. Everything downstream - readiness scoring, gap detection, case assembly, the
 * AI context - reads what this worker wrote.
 *
 * <p>A worker, not a service: the HTTP port serves {@code /actuator/health} and
 * {@code /actuator/prometheus} and nothing else.
 */
@SpringBootApplication
@EnableKafka
@EnableTransactionManagement
public class StateBuilderWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StateBuilderWorkerApplication.class, args);
    }
}
