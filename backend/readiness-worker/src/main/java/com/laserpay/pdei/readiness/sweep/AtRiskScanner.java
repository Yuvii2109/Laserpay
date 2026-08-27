package com.laserpay.pdei.readiness.sweep;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.readiness.config.ReadinessProperties;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import com.laserpay.pdei.readiness.persistence.ReadinessStore;
import com.laserpay.pdei.readiness.recompute.RecomputeDebouncer;
import com.laserpay.pdei.readiness.recompute.RecomputeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Materialises the at-risk feed that backs {@code GET /api/v1/gaps} and the Merchant Control Tower.
 *
 * <p>Two jobs, one scan:
 *
 * <ol>
 *   <li><strong>Materialise.</strong> Join the current readiness snapshots in a losing band with
 *       their worst unresolved gap, rank them, and keep the result where the read path can have it
 *       instantly. The query is a grouped join across two tables; running it per page view, per
 *       merchant, per dashboard refresh is exactly the kind of thing that makes a control tower
 *       feel slow.</li>
 *   <li><strong>Repair.</strong> A snapshot is only as good as the moment it was computed. Anything
 *       whose score has gone stale - and anything that has never been scored at all, which is the
 *       state of every transaction created while this worker was down - is handed to the debouncer
 *       for recomputation. This is the backstop that makes a lost Kafka event a latency problem
 *       rather than a permanently wrong number.</li>
 * </ol>
 *
 * <p>The feed is deliberately held in memory rather than in a new Redis key: the platform's Redis
 * namespace is fixed by PLATFORM-CONTRACT section 12, the authoritative rows already live in
 * {@code readiness_gaps}, and a per-replica cache of a query result needs no coordination. Each
 * replica materialises its own copy from the same database, so they agree.
 */
@Component
public class AtRiskScanner {

    private static final Logger log = LoggerFactory.getLogger(AtRiskScanner.class);

    private final ReadinessStore store;
    private final RecomputeDebouncer debouncer;
    private final ReadinessProperties properties;
    private final ReadinessWorkerMetrics metrics;
    private final Clocks clock;

    private volatile List<AtRiskEntry> feed = List.of();
    private volatile Instant lastScanAt;

    public AtRiskScanner(ReadinessStore store, RecomputeDebouncer debouncer,
                         ReadinessProperties properties, ReadinessWorkerMetrics metrics, Clocks clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.debouncer = Objects.requireNonNull(debouncer, "debouncer must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = metrics;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Scheduled entry point. {@code fixedDelay} rather than {@code fixedRate}: if a scan takes
     * longer than the interval, the next one waits instead of piling up behind it.
     *
     * <p>The two delay properties must be ISO-8601 ({@code PT5M}) or a plain millisecond count.
     * Spring's scheduler does not accept the {@code 5m} shorthand that {@code @ConfigurationProperties}
     * binding does - a difference that fails at startup, loudly, rather than silently.
     */
    @Scheduled(fixedDelayString = "${pdei.readiness.at-risk.interval:PT5M}",
            initialDelayString = "${pdei.readiness.at-risk.initial-delay:PT1M}")
    public void scheduledScan() {
        if (!properties.getAtRisk().isEnabled()) {
            return;
        }
        try {
            ScanResult result = scan();
            log.debug("at-risk scan: entries={} refreshed={} urgent={}",
                    result.entries(), result.refreshed(), result.urgent());
        } catch (RuntimeException e) {
            // A scanner that dies takes the repair backstop with it; keep the scheduler alive.
            log.error("at-risk scan failed: {}", e.toString(), e);
        }
    }

    /** Run one scan now and return what it found. */
    public ScanResult scan() {
        ReadinessProperties.AtRisk config = properties.getAtRisk();
        Instant now = clock.now();

        List<AtRiskEntry> materialised = store.findAtRisk(config.bandSet(), config.getLimit());
        this.feed = List.copyOf(materialised);
        this.lastScanAt = now;
        if (metrics != null) {
            metrics.atRiskFeedSize(materialised.size());
        }

        int refreshed = requeueStale(materialised, now, config);
        long urgent = materialised.stream().filter(AtRiskEntry::isUrgent).count();
        return new ScanResult(materialised.size(), refreshed, (int) urgent, now);
    }

    /**
     * Queue a recomputation for anything whose score can no longer be trusted.
     *
     * <p>Two populations, deduplicated so a transaction that is both at risk and stale is queued
     * once: at-risk transactions whose snapshot has aged past the threshold, and transactions with
     * no current snapshot at all.
     */
    private int requeueStale(List<AtRiskEntry> materialised, Instant now,
                             ReadinessProperties.AtRisk config) {
        Instant staleBefore = now.minus(config.getStaleAfter());
        Set<String> queued = new LinkedHashSet<>();

        for (AtRiskEntry entry : materialised) {
            if (entry.isStale(staleBefore) && queued.add(entry.transactionId())) {
                debouncer.submit(RecomputeRequest.stale(entry.transactionId(), entry.merchantId(), now));
            }
        }

        for (AtRiskEntry entry : store.findStale(staleBefore, config.getLimit())) {
            if (queued.add(entry.transactionId())) {
                debouncer.submit(RecomputeRequest.stale(entry.transactionId(), entry.merchantId(), now));
            }
        }
        return queued.size();
    }

    /** The most recently materialised feed, worst first. Never null. */
    public List<AtRiskEntry> feed() {
        return feed;
    }

    /** The feed filtered to one merchant. */
    public List<AtRiskEntry> feedFor(String merchantId) {
        if (merchantId == null) {
            return feed();
        }
        List<AtRiskEntry> filtered = new ArrayList<>();
        for (AtRiskEntry entry : feed) {
            if (merchantId.equals(entry.merchantId())) {
                filtered.add(entry);
            }
        }
        return List.copyOf(filtered);
    }

    /** When the feed was last materialised, or null before the first scan. */
    public Instant lastScanAt() {
        return lastScanAt;
    }

    /** Outcome of one scan. */
    public record ScanResult(int entries, int refreshed, int urgent, Instant scannedAt) {
    }
}
