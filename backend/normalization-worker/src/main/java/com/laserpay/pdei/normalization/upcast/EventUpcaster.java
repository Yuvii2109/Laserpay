package com.laserpay.pdei.normalization.upcast;

import com.laserpay.pdei.common.event.RawEventEnvelope;

/**
 * One step in the raw-event schema migration chain.
 *
 * <p>Source systems evolve on their own schedule, and {@code pdei.raw.events.v1} is retained so any
 * normalization bug can be fixed and the stream replayed. That combination means this worker will
 * always be asked to read payloads written by older producers. Rather than scattering
 * {@code if (legacyShape)} branches through every adapter, old shapes are migrated forward here,
 * once, before any adapter sees them.
 *
 * <p>An upcaster is a pure function: same input, same output, no I/O. It never drops information it
 * cannot map - it writes the modern shape alongside, so a later fix can still recover the original
 * from {@code pdei.raw.events.v1}.
 *
 * <p>Registration: declare a {@code @Bean} of this type. {@link UpcasterChain} orders by
 * {@link #fromVersion()} and applies repeatedly until no upcaster claims the envelope.
 */
public interface EventUpcaster {

    /**
     * Schema version this upcaster migrates <em>from</em>; it produces {@code fromVersion() + 1}.
     * Used only for ordering - {@link #supports(RawEventEnvelope)} is what decides applicability.
     */
    int fromVersion();

    /** True when this envelope is in the old shape this upcaster knows how to migrate. */
    boolean supports(RawEventEnvelope raw);

    /**
     * Returns the migrated envelope. Must return a new instance rather than mutating the argument;
     * {@link RawEventEnvelope} is a record and its body is shared with the dead-letter path, which
     * must still be able to show the original payload.
     */
    RawEventEnvelope upcast(RawEventEnvelope raw);

    /** Human-readable name for logs and metrics tags. */
    default String name() {
        return getClass().getSimpleName();
    }
}
