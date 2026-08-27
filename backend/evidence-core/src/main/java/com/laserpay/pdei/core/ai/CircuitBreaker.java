package com.laserpay.pdei.core.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal circuit breaker for the AI service call.
 *
 * <p>After {@code failureThreshold} consecutive failures the circuit opens for {@code openDuration},
 * during which calls are refused immediately and the caller falls back to the deterministic path.
 * The first call after the window is a probe: success closes the circuit, failure re-opens it.</p>
 *
 * <p>Deliberately hand-rolled rather than pulled from a resilience library: it is 40 lines, it has no
 * configuration surface to get wrong, and the platform has exactly one remote dependency to protect.</p>
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold <= 0 ? 5 : failureThreshold;
        this.openDuration = openDuration == null ? Duration.ofSeconds(60) : openDuration;
    }

    /** True when a call may be attempted. */
    public boolean allowRequest() {
        Instant opened = openedAt.get();
        if (opened == null) {
            return true;
        }
        if (Instant.now().isAfter(opened.plus(openDuration))) {
            // half-open: let one probe through
            openedAt.set(null);
            return true;
        }
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAt.set(null);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt.compareAndSet(null, Instant.now());
        }
    }

    public boolean isOpen() {
        return !allowRequest();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
