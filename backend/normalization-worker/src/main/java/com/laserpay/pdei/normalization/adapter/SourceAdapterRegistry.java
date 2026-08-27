package com.laserpay.pdei.normalization.adapter;

import com.laserpay.pdei.common.event.RawEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Resolves a {@link RawEventEnvelope} to the adapter that owns its source system.
 *
 * <p>Lookup is a hash on the normalized {@link RawEventEnvelope#sourceSystem()} - matching is
 * case-insensitive and ignores {@code -}, {@code _}, {@code /} and spaces, because
 * {@code ORDER_SYSTEM}, {@code order-system} and {@code OrderSystem} are the same thing to everyone
 * except a string comparison. A linear {@code supports()} scan is the documented fallback, so an
 * adapter can implement conditional claiming (payload sniffing, header inspection) without changing
 * this class.
 *
 * <p>An unresolvable source system is <em>not</em> an error to swallow: the caller dead-letters the
 * record with the source system name in the failure message, which is exactly the signal that a new
 * upstream integration went live without an adapter.
 */
public class SourceAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceAdapterRegistry.class);

    private final List<SourceAdapter> adapters;
    private final Map<String, SourceAdapter> byAlias;

    public SourceAdapterRegistry(Collection<SourceAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        Map<String, SourceAdapter> index = new LinkedHashMap<>();
        for (SourceAdapter adapter : this.adapters) {
            register(index, adapter.sourceSystem(), adapter);
            for (String alias : adapter.aliases()) {
                register(index, alias, adapter);
            }
        }
        this.byAlias = Map.copyOf(index);
        log.info("source adapter registry ready: {} adapters, {} aliases -> {}",
                this.adapters.size(), this.byAlias.size(), describe());
    }

    private static void register(Map<String, SourceAdapter> index, String alias, SourceAdapter adapter) {
        String key = AbstractSourceAdapter.normalizeKey(alias);
        SourceAdapter previous = index.putIfAbsent(key, adapter);
        if (previous != null && previous != adapter) {
            // Ambiguity here would silently route a source system's events through the wrong
            // vocabulary, which is a data-corruption bug rather than a configuration nit.
            throw new IllegalStateException("source alias '" + alias + "' is claimed by both "
                    + previous.getClass().getSimpleName() + " and " + adapter.getClass().getSimpleName());
        }
    }

    /** The adapter owning this envelope's source system, if any. */
    public Optional<SourceAdapter> find(RawEventEnvelope raw) {
        if (raw == null) {
            return Optional.empty();
        }
        SourceAdapter direct = byAlias.get(AbstractSourceAdapter.normalizeKey(raw.sourceSystem()));
        if (direct != null && direct.supports(raw)) {
            return Optional.of(direct);
        }
        return adapters.stream().filter(adapter -> adapter.supports(raw)).findFirst();
    }

    /**
     * The adapter owning this envelope's source system.
     *
     * @throws UnmappableEventException when no adapter claims it - the record is dead-lettered
     */
    public SourceAdapter require(RawEventEnvelope raw) {
        return find(raw).orElseThrow(() -> new UnmappableEventException(
                raw == null ? null : raw.sourceSystem(),
                raw == null ? null : raw.sourceEventType(),
                "no SourceAdapter is registered for this source system; known systems=" + sourceSystems()));
    }

    public List<SourceAdapter> adapters() {
        return adapters;
    }

    /** Canonical source system names, for logs and a future {@code GET /schemas} listing. */
    public List<String> sourceSystems() {
        return adapters.stream().map(SourceAdapter::sourceSystem).toList();
    }

    /** Source system to accepted source event types - the registry's self-description. */
    public Map<String, Object> describe() {
        Map<String, Object> description = new TreeMap<>();
        for (SourceAdapter adapter : adapters) {
            description.put(adapter.sourceSystem(), adapter instanceof AbstractSourceAdapter base
                    ? base.supportedSourceEventTypes()
                    : List.of());
        }
        return description;
    }
}
