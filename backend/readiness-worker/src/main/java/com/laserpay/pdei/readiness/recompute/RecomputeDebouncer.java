package com.laserpay.pdei.readiness.recompute;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.readiness.config.ReadinessProperties;
import com.laserpay.pdei.readiness.metrics.ReadinessWorkerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Collapses a burst of events for one transaction into a single readiness computation.
 *
 * <p>One captured payment fans out into an order record, an invoice, a shipment, a delivery proof
 * and a policy snapshot within a second or two. Scoring the transaction five times produces five
 * snapshots, four of which nobody will ever read, and five sets of Kafka events for the control
 * tower to redraw. This class exists so that costs one computation.
 *
 * <p><strong>Sliding window with a ceiling.</strong> The first event for a transaction schedules a
 * computation {@code debounce} from now. Each further event inside the window merges into the
 * pending request (keeping the most specific trigger) and slides the deadline - but never past
 * {@code firstSeenAt + maxDebounceDelay}. Without that ceiling a transaction under continuous
 * traffic would defer forever and its readiness would never refresh.
 *
 * <p><strong>This is only the in-process half of the debounce.</strong> Several worker replicas
 * each hold their own map, so the cross-process half is the Redis lock
 * {@code pdei:lock:readiness:{transactionId}} taken by {@link ReadinessRecomputeService}: whichever
 * replica fires first computes, the others find the lock held and coalesce. Kafka's partitioning by
 * {@code merchantId + ":" + aggregateId} already keeps most of a transaction's traffic on one
 * replica; the lock covers the rest.
 *
 * <p>Firing dispatches to a separate executor so a slow database never blocks the scheduler thread
 * and delays every other transaction's debounce.
 */
public class RecomputeDebouncer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecomputeDebouncer.class);

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Executor workers;
    private final Consumer<RecomputeRequest> action;
    private final Clocks clock;
    private final ReadinessWorkerMetrics metrics;
    private final Duration debounce;
    private final Duration maxDelay;
    private final boolean ownsExecutors;

    /**
     * Build a debouncer that owns its own scheduler and worker pool, sized from
     * {@code pdei.readiness.worker-threads}.
     *
     * <p>Preferred in the application, because these executors must <em>not</em> be exposed as
     * beans: a lone {@code ScheduledExecutorService} bean is adopted by {@code @EnableScheduling} as
     * the task scheduler, and the nightly sweep would then compete with every debounce window for
     * the same threads.
     */
    public static RecomputeDebouncer create(Consumer<RecomputeRequest> action, Clocks clock,
                                            ReadinessProperties properties,
                                            ReadinessWorkerMetrics metrics) {
        int threads = Math.max(1, properties.getWorkerThreads());
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.max(1, Math.min(2, threads)), namedThreads("pdei-readiness-debounce-"));
        ExecutorService workers = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                namedThreads("pdei-readiness-recompute-"),
                // A saturated queue means the database is the bottleneck. Running the computation on
                // the calling thread applies natural back-pressure to the Kafka listener instead of
                // discarding a readiness update.
                new ThreadPoolExecutor.CallerRunsPolicy());
        return new RecomputeDebouncer(scheduler, workers, action, clock, properties, metrics, true);
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Constructor for tests and for callers that manage the executors themselves. */
    public RecomputeDebouncer(ScheduledExecutorService scheduler, Executor workers,
                              Consumer<RecomputeRequest> action, Clocks clock,
                              ReadinessProperties properties, ReadinessWorkerMetrics metrics) {
        this(scheduler, workers, action, clock, properties, metrics, false);
    }

    private RecomputeDebouncer(ScheduledExecutorService scheduler, Executor workers,
                               Consumer<RecomputeRequest> action, Clocks clock,
                               ReadinessProperties properties, ReadinessWorkerMetrics metrics,
                               boolean ownsExecutors) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.workers = Objects.requireNonNull(workers, "workers must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.metrics = metrics;
        this.debounce = positive(properties.getDebounce(), Duration.ofSeconds(2));
        this.maxDelay = positive(properties.getMaxDebounceDelay(), Duration.ofSeconds(30));
        this.ownsExecutors = ownsExecutors;
    }

    /**
     * Register a recomputation request. Returns immediately; the computation happens later, on
     * another thread, at most once per debounce window per transaction.
     *
     * @return true when this call started a new window, false when it merged into an open one
     */
    public boolean submit(RecomputeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String transactionId = request.transactionId();

        Pending entry = pending.compute(transactionId, (key, existing) -> {
            if (existing == null) {
                return new Pending(request, clock.now());
            }
            existing.merge(request);
            return existing;
        });

        boolean isNew = entry.claimIfFresh();
        if (isNew) {
            schedule(entry, debounce);
        } else {
            coalesce(entry);
        }
        report();
        return isNew;
    }

    /**
     * Slide the deadline of an open window, unless the ceiling has been reached.
     *
     * <p>Cancelling and rescheduling is cheap: these are single tasks on a shared scheduler, not
     * threads.
     */
    private void coalesce(Pending entry) {
        if (metrics != null) {
            metrics.recomputeCoalesced();
        }
        Instant now = clock.now();
        Instant ceiling = entry.firstSeenAt().plus(maxDelay);
        Instant proposed = now.plus(debounce);
        if (!proposed.isBefore(ceiling)) {
            // Ceiling reached: leave the already-scheduled task alone so the computation still runs.
            return;
        }
        boolean rescheduled = entry.reschedule(() -> schedule(entry, debounce));
        if (!rescheduled && pending.get(entry.transactionId()) != entry) {
            // The window fired between our merge and our reschedule: this entry is orphaned and the
            // just-merged facts would be lost. Open a fresh window for them instead.
            submit(entry.request());
        }
    }

    private void schedule(Pending entry, Duration delay) {
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fire(entry.transactionId()), Math.max(1L, delay.toMillis()), TimeUnit.MILLISECONDS);
        entry.setFuture(future);
    }

    /**
     * Remove the pending entry and hand the merged request to the executor.
     *
     * <p>Removal happens <em>before</em> execution on purpose: an event that arrives while the
     * computation is running opens a fresh window and gets its own computation, because it may
     * carry facts the running one has already read past.
     */
    private void fire(String transactionId) {
        Pending entry = pending.remove(transactionId);
        report();
        if (entry == null) {
            return;
        }
        RecomputeRequest request = entry.request();
        try {
            workers.execute(() -> run(request));
        } catch (RuntimeException e) {
            // Rejected by a saturated or shut-down executor: run inline rather than lose the work.
            log.warn("recompute executor rejected transactionId={}, running inline: {}",
                    transactionId, e.toString());
            run(request);
        }
    }

    private void run(RecomputeRequest request) {
        try {
            action.accept(request);
        } catch (RuntimeException e) {
            log.error("readiness recomputation failed for transactionId={} trigger={}: {}",
                    request.transactionId(), request.trigger(), e.toString(), e);
        }
    }

    /**
     * Run every open window immediately.
     *
     * <p>Used on graceful shutdown so in-flight debounces are not silently discarded, and by tests
     * that would rather assert than sleep.
     */
    public void flushAll() {
        List<String> transactionIds = new ArrayList<>(pending.keySet());
        for (String transactionId : transactionIds) {
            Pending entry = pending.get(transactionId);
            if (entry != null) {
                entry.cancel();
            }
            fire(transactionId);
        }
    }

    /** Open debounce windows. */
    public int pendingCount() {
        return pending.size();
    }

    /** The merged request currently queued for a transaction, if any. Diagnostics and tests. */
    public RecomputeRequest peek(String transactionId) {
        Pending entry = pending.get(transactionId);
        return entry == null ? null : entry.request();
    }

    /**
     * Graceful shutdown: run every open window, then stop the executors this instance created.
     *
     * <p>Discarding a pending debounce on shutdown would leave a transaction with a score that
     * silently predates the events already acknowledged from Kafka.
     */
    @Override
    public void close() {
        flushAll();
        if (!ownsExecutors) {
            return;
        }
        scheduler.shutdown();
        if (workers instanceof ExecutorService executorService) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(20, TimeUnit.SECONDS)) {
                    log.warn("readiness recompute pool did not drain within 20s; forcing shutdown");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
        }
    }

    private void report() {
        if (metrics != null) {
            metrics.pendingRecomputes(pending.size());
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    /**
     * One open debounce window.
     *
     * <p>Mutable and guarded by its own monitor. The map holds at most one instance per transaction,
     * so contention is per transaction and never global.
     */
    private static final class Pending {

        private final Instant firstSeenAt;
        private RecomputeRequest request;
        private ScheduledFuture<?> future;
        private boolean claimed;

        Pending(RecomputeRequest request, Instant firstSeenAt) {
            this.request = request;
            this.firstSeenAt = firstSeenAt == null ? request.requestedAt() : firstSeenAt;
        }

        synchronized void merge(RecomputeRequest other) {
            request = request.mergedWith(other);
        }

        /** True exactly once per window: the caller that must schedule the initial task. */
        synchronized boolean claimIfFresh() {
            if (claimed) {
                return false;
            }
            claimed = true;
            return true;
        }

        /** @return true when the pending task was replaced, false when it had already fired */
        synchronized boolean reschedule(Runnable rescheduler) {
            if (future != null && !future.cancel(false)) {
                // Already running or done; the fire path owns it now.
                return false;
            }
            rescheduler.run();
            return true;
        }

        synchronized void setFuture(ScheduledFuture<?> value) {
            future = value;
        }

        synchronized void cancel() {
            if (future != null) {
                future.cancel(false);
            }
        }

        synchronized RecomputeRequest request() {
            return request;
        }

        String transactionId() {
            return request().transactionId();
        }

        Instant firstSeenAt() {
            return firstSeenAt;
        }
    }

    /** Snapshot of the debouncer state, for the actuator info contributor and tests. */
    public Map<String, Object> stats() {
        return Map.of(
                "pending", pending.size(),
                "debounceMillis", debounce.toMillis(),
                "maxDelayMillis", maxDelay.toMillis());
    }
}
