package com.laserpay.pdei.audit;

import com.laserpay.pdei.audit.config.AuditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PDEI audit service (docs/PLATFORM-CONTRACT.md section 2, port 8087).
 *
 * <p>The tamper-evident record of everything the platform did. Every other service reports what it
 * changed by publishing an {@code AuditEvent}; this one is the single writer that turns those
 * reports into an append-only, hash-chained history and the only place that can answer "has this
 * history been altered?".
 *
 * <p>Three guarantees define the service:
 *
 * <ol>
 *   <li><strong>Append-only.</strong> There is no update path and no delete path in this codebase.
 *       {@code V8__audit.sql} additionally installs a trigger that rejects UPDATE and DELETE at the
 *       database, so even a stray {@code psql} session cannot rewrite history quietly.</li>
 *   <li><strong>Chained.</strong> Each row stores the hash of its predecessor, and its own hash
 *       covers that link, so altering any historical row invalidates every hash after it. Chains are
 *       per merchant: one merchant's history verifies without reading anyone else's, and one noisy
 *       merchant cannot serialise the whole platform.</li>
 *   <li><strong>Verifiable.</strong> {@code GET /audit/v1/chain/verify} recomputes a chain and
 *       reports the first divergence with the audit id, the expected hash, the actual hash and the
 *       index at which they parted company.</li>
 * </ol>
 *
 * <p>Nothing here is probabilistic and nothing here calls a model (non-negotiable rules 1, 2 and 8).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AuditProperties.class)
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
