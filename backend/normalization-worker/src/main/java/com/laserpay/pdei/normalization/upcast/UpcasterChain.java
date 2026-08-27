package com.laserpay.pdei.normalization.upcast;

import com.laserpay.pdei.common.event.CanonicalEvent;
import com.laserpay.pdei.common.event.RawEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies {@link EventUpcaster}s until the envelope is in the current schema shape.
 *
 * <p>Ordered by {@link EventUpcaster#fromVersion()} and applied repeatedly, so a v0 payload can walk
 * v0 to v1 to v2 in one pass without any upcaster knowing about its successors. Each upcaster is
 * applied at most once per pass and the loop is bounded by {@link #MAX_PASSES}: an upcaster whose
 * {@code supports()} stays true after its own {@code upcast()} would otherwise spin forever, and a
 * hung consumer is a worse failure than a dead letter.
 *
 * <p>The chain is a no-op for the common case - a current-shape payload matches no upcaster and is
 * returned unchanged, with no copying.
 */
public class UpcasterChain {

    private static final Logger log = LoggerFactory.getLogger(UpcasterChain.class);

    /** Bound on chain length. Ten hops is far beyond any realistic schema history. */
    static final int MAX_PASSES = 10;

    private final List<EventUpcaster> upcasters;

    public UpcasterChain(List<EventUpcaster> upcasters) {
        List<EventUpcaster> ordered = new ArrayList<>(upcasters);
        ordered.sort(Comparator.comparingInt(EventUpcaster::fromVersion));
        this.upcasters = List.copyOf(ordered);
        log.info("upcaster chain ready: {}", this.upcasters.stream().map(EventUpcaster::name).toList());
    }

    /** An empty chain, for tests and for deployments with no legacy producers. */
    public static UpcasterChain empty() {
        return new UpcasterChain(List.of());
    }

    /**
     * Migrates the envelope forward to the current schema shape.
     *
     * @return the migrated envelope, or the original instance when nothing applied
     */
    public RawEventEnvelope upcast(RawEventEnvelope raw) {
        if (raw == null || upcasters.isEmpty()) {
            return raw;
        }
        RawEventEnvelope current = raw;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            EventUpcaster applicable = null;
            for (EventUpcaster upcaster : upcasters) {
                if (upcaster.supports(current)) {
                    applicable = upcaster;
                    break;
                }
            }
            if (applicable == null) {
                return current;
            }
            RawEventEnvelope migrated = applicable.upcast(current);
            if (migrated == null || migrated == current) {
                log.warn("upcaster {} claimed rawEventId={} but produced no change; stopping chain",
                        applicable.name(), current.rawEventId());
                return current;
            }
            log.debug("upcast rawEventId={} via {} (v{} -> v{})", current.rawEventId(),
                    applicable.name(), applicable.fromVersion(), applicable.fromVersion() + 1);
            current = migrated;
        }
        log.warn("upcaster chain hit the {}-pass bound for rawEventId={}; using the last shape",
                MAX_PASSES, current.rawEventId());
        return current;
    }

    /**
     * Migrates and then stamps the current schema version, so an adapter always sees a
     * version-labelled envelope even when the producer sent none.
     */
    public RawEventEnvelope upcastAndStamp(RawEventEnvelope raw) {
        RawEventEnvelope migrated = upcast(raw);
        return migrated == null
                ? null
                : SchemaVersions.withVersion(migrated, CanonicalEvent.CURRENT_SCHEMA_VERSION);
    }

    public List<EventUpcaster> upcasters() {
        return upcasters;
    }
}
