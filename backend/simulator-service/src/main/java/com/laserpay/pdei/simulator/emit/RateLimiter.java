package com.laserpay.pdei.simulator.emit;

/**
 * Token bucket that paces event publication.
 *
 * <p>Why pacing matters for a simulator: an unthrottled loop publishes a hundred thousand events
 * into Kafka in a couple of seconds, every consumer lags by minutes, and the demo shows a queue
 * draining rather than a system working. A configurable rate also makes benchmarks meaningful -
 * "500 events/second sustained, consumer lag under N" is a claim; "as fast as the loop went" is
 * not.
 *
 * <p>Deliberately simple: one bucket, one lock, {@link Thread#sleep} when empty. Per-run
 * emission is single-threaded, so contention is not a concern and a scheduled-executor design
 * would add machinery for nothing. Time comes from {@link System#nanoTime()} because this is
 * wall-clock pacing of a real process, not simulated time - the generated world's clock is a
 * separate concern that lives in {@code WorldSpec.startAt}.
 */
public class RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final double permitsPerSecond;
    private final double burstCapacity;

    private double available;
    private long lastRefillNanos;

    /**
     * @param permitsPerSecond target rate; zero or negative means unlimited
     */
    public RateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        // One second of burst: absorbs the jitter of a batching producer without letting a long
        // idle period bank an unbounded flood.
        this.burstCapacity = Math.max(1.0, permitsPerSecond);
        this.available = this.burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Unlimited limiter, for tests and for {@code eventsPerSecond <= 0}. */
    public static RateLimiter unlimited() {
        return new RateLimiter(0);
    }

    public boolean isUnlimited() {
        return permitsPerSecond <= 0;
    }

    /**
     * Blocks until one permit is available.
     *
     * @throws InterruptedException when the run is stopped while waiting
     */
    public void acquire() throws InterruptedException {
        if (isUnlimited()) {
            return;
        }
        long sleepNanos;
        synchronized (this) {
            refill();
            if (available >= 1.0) {
                available -= 1.0;
                return;
            }
            double deficit = 1.0 - available;
            sleepNanos = (long) (deficit / permitsPerSecond * NANOS_PER_SECOND);
            available = 0.0;
        }
        // Sleep outside the monitor: holding it would serialise every other caller behind this
        // wait for no reason.
        if (sleepNanos > 0) {
            Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
        }
    }

    /** Current rate, for the progress model. */
    public int permitsPerSecond() {
        return (int) permitsPerSecond;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        lastRefillNanos = now;
        available = Math.min(burstCapacity,
                available + (double) elapsed / NANOS_PER_SECOND * permitsPerSecond);
    }
}
