package com.laserpay.pdei.docproc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for document-processor-service, bound from the {@code pdei.docproc} prefix.
 *
 * <p>Every value here is a guard rail. Document processing is the one place in PDEI where the
 * service hands attacker-influenced bytes to a third-party parser, so size, time and output are
 * all bounded before anything is parsed and the failure mode is quarantine, never an OOM in a
 * Kafka consumer thread.
 */
@ConfigurationProperties(prefix = "pdei.docproc")
public class DocProcProperties {

    /** Hard ceiling on the artifact size fetched from MinIO. Larger objects go to quarantine. */
    private long maxObjectBytes = 25L * 1024 * 1024;

    /**
     * Ceiling on characters written into {@code evidence.extracted_text}. Postgres can hold far
     * more, but the tsvector cannot: a single {@code tsvector} is capped at 1 MB, and an
     * oversized document silently fails to index. Truncating here keeps search honest.
     */
    private int maxTextChars = 500_000;

    /** Write limit handed to Tika, above which it signals truncation instead of buffering more. */
    private int tikaWriteLimitChars = 600_000;

    /** Ceiling on PDF pages read. Beyond this the result carries a page-limit warning. */
    private int maxPdfPages = 500;

    /** Wall-clock budget for one extraction. Exceeding it quarantines the artifact. */
    private Duration extractionTimeout = Duration.ofSeconds(30);

    /** Size of the extraction worker pool that enforces {@link #extractionTimeout}. */
    private int extractorThreads = 4;

    /** Most recent quarantine entries kept in memory for {@code GET /docproc/v1/stats}. */
    private int quarantineHistorySize = 100;

    /**
     * Re-extract even when the evidence row already holds text for the same sha256. Off by
     * default: the consumer is idempotent, and a redelivered EvidenceAdded must not repeatedly
     * re-parse the same bytes. {@code POST /reprocess/{evidenceId}} forces it regardless.
     */
    private boolean reextractUnchanged = false;

    /**
     * Publish an EVIDENCE event after a successful extraction so readiness recomputes against
     * the newly searchable text (platform contract 7: any EVIDENCE event triggers recomputation).
     */
    private boolean publishEvidenceEvents = true;

    /** Kafka consumer switch, so the REST surface can be exercised without a broker. */
    private boolean consumerEnabled = true;

    /** Redis-backed first-line dedupe in front of the Postgres {@code processed_events} claim. */
    private boolean redisDedupeEnabled = true;

    /** TTL of {@code pdei:idem:{eventId}} (platform contract 12). */
    private Duration idempotencyTtl = Duration.ofDays(7);

    public long getMaxObjectBytes() {
        return maxObjectBytes;
    }

    public void setMaxObjectBytes(long maxObjectBytes) {
        this.maxObjectBytes = maxObjectBytes;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public int getTikaWriteLimitChars() {
        return tikaWriteLimitChars;
    }

    public void setTikaWriteLimitChars(int tikaWriteLimitChars) {
        this.tikaWriteLimitChars = tikaWriteLimitChars;
    }

    public int getMaxPdfPages() {
        return maxPdfPages;
    }

    public void setMaxPdfPages(int maxPdfPages) {
        this.maxPdfPages = maxPdfPages;
    }

    public Duration getExtractionTimeout() {
        return extractionTimeout;
    }

    public void setExtractionTimeout(Duration extractionTimeout) {
        this.extractionTimeout = extractionTimeout;
    }

    public int getExtractorThreads() {
        return extractorThreads;
    }

    public void setExtractorThreads(int extractorThreads) {
        this.extractorThreads = extractorThreads;
    }

    public int getQuarantineHistorySize() {
        return quarantineHistorySize;
    }

    public void setQuarantineHistorySize(int quarantineHistorySize) {
        this.quarantineHistorySize = quarantineHistorySize;
    }

    public boolean isReextractUnchanged() {
        return reextractUnchanged;
    }

    public void setReextractUnchanged(boolean reextractUnchanged) {
        this.reextractUnchanged = reextractUnchanged;
    }

    public boolean isPublishEvidenceEvents() {
        return publishEvidenceEvents;
    }

    public void setPublishEvidenceEvents(boolean publishEvidenceEvents) {
        this.publishEvidenceEvents = publishEvidenceEvents;
    }

    public boolean isConsumerEnabled() {
        return consumerEnabled;
    }

    public void setConsumerEnabled(boolean consumerEnabled) {
        this.consumerEnabled = consumerEnabled;
    }

    public boolean isRedisDedupeEnabled() {
        return redisDedupeEnabled;
    }

    public void setRedisDedupeEnabled(boolean redisDedupeEnabled) {
        this.redisDedupeEnabled = redisDedupeEnabled;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }
}
