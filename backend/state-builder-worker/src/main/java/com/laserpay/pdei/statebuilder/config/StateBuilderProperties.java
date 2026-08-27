package com.laserpay.pdei.statebuilder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for state-builder-worker, bound from {@code pdei.state-builder.*}.
 */
@ConfigurationProperties(prefix = "pdei.state-builder")
public class StateBuilderProperties {

    /**
     * ISO-4217 code used for a projection row that must have a currency before any event has stated
     * one - a stub order created to satisfy a foreign key, for example. Never used for an amount
     * other than zero.
     */
    private String defaultCurrency = "INR";

    /** Broker acknowledgement wait when forwarding to the evidence and dispute topics. */
    private Duration publishTimeout = Duration.ofSeconds(10);

    /**
     * Consumer threads. Note that ordering is per partition, and the partition key is
     * {@code merchantId + ":" + aggregateId}: raising this parallelises across aggregates without
     * ever reordering one aggregate's events.
     */
    private int concurrency = 3;

    /** Records fetched per poll. Kept modest because each record does database work. */
    private int maxPollRecords = 50;

    /** Set false to project financial state without deriving evidence (useful when profiling). */
    private boolean deriveEvidence = true;

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

    public boolean isDeriveEvidence() {
        return deriveEvidence;
    }

    public void setDeriveEvidence(boolean deriveEvidence) {
        this.deriveEvidence = deriveEvidence;
    }

    public Retry getRetry() {
        return retry;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    /**
     * Exponential backoff for transient failures. An optimistic-locking clash between two consumer
     * threads touching the same transaction row is the common case, and it succeeds on the retry.
     */
    public static class Retry {

        private int maxAttempts = 5;
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

        private boolean redisEnabled = true;
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
