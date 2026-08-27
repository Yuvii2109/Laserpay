package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.EventSource;
import com.laserpay.pdei.common.event.RawEventEnvelope;

import java.time.Instant;
import java.util.Set;

/**
 * Translates one source system's event shape into the canonical envelope
 * (PLATFORM-CONTRACT section 3, reference section 10).
 *
 * <p>One adapter per source system, and the adapter is the <em>only</em> place in the platform that
 * knows that system's vocabulary. Everything downstream of normalization speaks
 * {@link CanonicalEvent} and nothing else, which is what makes adding a fifth PSP a single new
 * class rather than a change rippling through seven services.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@code occurredAt} is taken from the source payload and preserved exactly - it is when the
 *       fact happened upstream. {@code observedAt} is supplied by the caller and stamped at
 *       normalization time. The gap between them is lateness, and it must stay visible.</li>
 *   <li>The canonical {@code eventId} must be a deterministic function of the raw event
 *       (see {@link AbstractSourceAdapter#canonicalEventId}), so replaying
 *       {@code pdei.raw.events.v1} re-emits identical ids and downstream idempotency suppresses
 *       the duplicates instead of double-applying them.</li>
 *   <li>Monetary values are read through {@code Payloads.money} into
 *       {@code (long amountMinor, String currency)}. No floating point, ever.</li>
 *   <li>An event this adapter cannot map must raise {@link UnmappableEventException} rather than
 *       returning null or a half-populated envelope. The listener dead-letters it.</li>
 * </ul>
 */
public interface SourceAdapter {

    /** Canonical name of the source system this adapter owns, e.g. {@code PSP}. */
    String sourceSystem();

    /**
     * Accepted spellings of {@link RawEventEnvelope#sourceSystem()} that resolve to this adapter -
     * vendor names included, because ingestion labels a webhook with whatever the vendor is called.
     * Matching is case-insensitive and ignores {@code -}, {@code _} and spaces.
     */
    Set<String> aliases();

    /** Provenance stamped onto every event this adapter produces. */
    EventSource eventSource();

    /** True when this adapter can handle the envelope. Default: alias match on the source system. */
    boolean supports(RawEventEnvelope raw);

    /**
     * Maps the raw envelope onto the canonical envelope.
     *
     * @param observedAt when PDEI observed the fact; stamped by the caller so every event
     *                   normalized in one pass shares an observation instant
     * @throws UnmappableEventException when the source event type is unknown to this adapter or a
     *                                  field the canonical envelope requires is missing
     */
    CanonicalEvent normalize(RawEventEnvelope raw, Instant observedAt);
}
