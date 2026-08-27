package com.laserpay.pdei.normalization.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for normalization-worker, bound from {@code pdei.normalization.*}.
 *
 * <p>Defaults are the values the local stack runs with; every one of them is safe in production
 * except {@link #getDefaultCurrency()}, which should be reviewed per deployment because it is the
 * fallback used when a source system omits a currency code.
 */
@ConfigurationProperties(prefix = "pdei.normalization")
public class NormalizationProperties {

    /**
     * ISO-4217 code used when a source omits the currency on a monetary field. A documented
     * deterministic fallback rather than a per-event guess: an amount whose currency is unknown
     * would otherwise have to be dropped.
     */
    private String defaultCurrency = "INR";

    /** How long to wait for the broker to acknowledge a canonical event before failing the batch. */
    private Duration publishTimeout = Duration.ofSeconds(10);

    /** Lag beyond which an event is logged as unusually late. Never a failure - just a signal. */
    private Duration latenessWarnThreshold = Duration.ofMinutes(30);

    /** Consumer threads. One per partition subset; 12 partitions divide evenly by 3. */
    private int concurrency = 3;

    /** Records fetched per poll. */
    private int maxPollRecords = 100;

    private final Retry retry = new Retry();
    private final Idempotency idempotency = new Idempotency();

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public Duration getPublishTimeout() {
        return publishTimeout;
    }

    public void setPublishTimeout(Duration publishTimeout) {
        this.publishTimeout = publishTimeout;
    }

    public Duration getLatenessWarnThreshold() {
        return latenessWarnThreshold;
    }

    public void setLatenessWarnThreshold(Duration latenessWarnThreshold) {
        this.latenessWarnThreshold = latenessWarnThreshold;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }

    public Retry getRetry() {
        return retry;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    /**
     * Exponential backoff for transient failures (broker unavailable, database blip).
     * Non-retryable failures - anything that will fail identically on the next attempt - bypass
     * this entirely and are dead-lettered on the first exception.
     */
    public static class Retry {

        private int maxAttempts = 4;
        private Duration initialInterval = Duration.ofSeconds(1);
        private double multiplier = 2.0d;
        private Duration maxInterval = Duration.ofSeconds(30);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }
    }

    /** Dedupe cache settings; Postgres {@code processed_events} is authoritative regardless. */
    public static class Idempotency {

        /** Set false to bypass Redis entirely and rely on Postgres alone. */
        private boolean redisEnabled = true;

        /** Matches the {@code pdei:idem:{eventId}} TTL in PLATFORM-CONTRACT section 12. */
        private Duration ttl = Duration.ofDays(7);

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }
}
