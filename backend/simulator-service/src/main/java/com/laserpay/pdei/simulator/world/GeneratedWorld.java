package com.laserpay.pdei.simulator.world;

import com.laserpay.pdei.common.money.Money;

import java.util.List;
import java.util.Map;

/**
 * The output of one {@link WorldGenerator} run: an ordered event stream plus the index the
 * emitter, the chaos engine and the tests need to work with it.
 *
 * @param spec          the spec that produced this world
 * @param events        the full event stream in emission order
 * @param merchantIds   generated merchants, in generation order
 * @param transactionIds generated transactions, in generation order
 * @param disputedTransactionIds transactions that end in a dispute
 * @param evidenceIds   generated evidence ids, in generation order
 * @param counts        headline counts for the run progress model
 * @param grossValue    sum of every generated transaction amount
 */
public record GeneratedWorld(WorldSpec spec,
                             List<SimEvent> events,
                             List<String> merchantIds,
                             List<String> transactionIds,
                             List<String> disputedTransactionIds,
                             List<String> evidenceIds,
                             Map<String, Long> counts,
                             Money grossValue) {

    public static final String COUNT_EVENTS = "events";
    public static final String COUNT_MERCHANTS = "merchants";
    public static final String COUNT_CUSTOMERS = "customers";
    public static final String COUNT_TRANSACTIONS = "transactions";
    public static final String COUNT_EVIDENCE = "evidence";
    public static final String COUNT_DISPUTES = "disputes";
    public static final String COUNT_SHIPMENTS = "shipments";
    public static final String COUNT_REFUNDS = "refunds";
    public static final String COUNT_COMMUNICATIONS = "communications";
    public static final String COUNT_LATE_EVENTS = "lateEvents";
    public static final String COUNT_DUPLICATE_EVENTS = "duplicateEvents";
    public static final String COUNT_DROPPED_EVENTS = "droppedEvents";

    public GeneratedWorld {
        events = List.copyOf(events);
        merchantIds = List.copyOf(merchantIds);
        transactionIds = List.copyOf(transactionIds);
        disputedTransactionIds = List.copyOf(disputedTransactionIds);
        evidenceIds = List.copyOf(evidenceIds);
        counts = Map.copyOf(counts);
    }

    public int eventCount() {
        return events.size();
    }

    public long count(String key) {
        return counts.getOrDefault(key, 0L);
    }

    /** Every artifact that has bytes, for the uploader. */
    public List<SyntheticArtifact> artifacts() {
        return events.stream()
                .map(SimEvent::artifact)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
