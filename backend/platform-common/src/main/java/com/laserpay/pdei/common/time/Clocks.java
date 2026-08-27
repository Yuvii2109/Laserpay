package com.laserpay.pdei.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The only way PDEI code reads the current time (docs/SHARED-LIBRARY-API.md section 1.9).
 *
 * <p>Nothing calls {@code Instant.now()} directly in domain code: expiry transitions, readiness
 * penalties, deadline urgency and the nightly sweep all depend on "now", and they must be testable
 * and reproducible. Inject a {@link Clocks} and the simulator can run seven simulated days in a
 * second while tests pin an exact instant.
 *
 * <p>Always UTC, always {@link Instant}. {@code LocalDateTime} appears nowhere in this platform.
 */
@FunctionalInterface
public interface Clocks {

    Instant now();

    /** Wall-clock time from the system UTC clock. */
    static Clocks system() {
        return Instant::now;
    }

    /** A clock frozen at {@code i}; the workhorse of deterministic tests. */
    static Clocks fixed(Instant i) {
        Objects.requireNonNull(i, "instant must not be null");
        return () -> i;
    }

    /** Adapter for an existing {@link java.time.Clock}. */
    static Clocks of(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return clock::instant;
    }

    /** Current time truncated to milliseconds, matching the JSON/TIMESTAMPTZ wire precision. */
    default Instant nowMillis() {
        return Instant.ofEpochMilli(now().toEpochMilli());
    }

    /** View of this clock as a {@link java.time.Clock} for APIs that demand one. */
    default Clock toJavaClock() {
        Clocks self = this;
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return self.now();
            }
        };
    }
}
