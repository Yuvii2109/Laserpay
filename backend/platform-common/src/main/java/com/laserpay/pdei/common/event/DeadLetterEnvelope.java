package com.laserpay.pdei.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.hash.Hashes;
import com.laserpay.pdei.common.json.Json;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/**
 * What every consumer publishes to {@code pdei.dlq.v1} when a record cannot be processed
 * (PLATFORM-CONTRACT section 4).
 *
 * <p>It carries enough coordinates ({@code originalTopic}, {@code partition}, {@code offset},
 * {@code consumerGroup}) to replay the exact record after a fix, and the original payload so a
 * replay does not depend on the source topic still retaining the data.
 *
 * <p>{@code stackTraceDigest} is a hash rather than the stack trace itself: it groups recurring
 * failures in dashboards without filling Kafka with megabytes of duplicated traces. The readable
 * trace stays in the logs, correlated through the trace id.
 */
public record DeadLetterEnvelope(String originalTopic,
                                 int partition,
                                 long offset,
                                 String consumerGroup,
                                 String failureClass,
                                 String failureMessage,
                                 String stackTraceDigest,
                                 Instant failedAt,
                                 int attempt,
                                 JsonNode originalPayload) {

    public DeadLetterEnvelope {
        failedAt = failedAt == null ? Instant.now() : failedAt;
        attempt = Math.max(1, attempt);
        originalPayload = originalPayload == null ? Json.mapper().nullNode() : originalPayload;
    }

    /**
     * Builds an envelope from the failure itself.
     *
     * @param originalPayload the record value as JSON; may be a raw text node when the value did
     *                        not parse, which is itself a common dead-letter cause.
     */
    public static DeadLetterEnvelope from(String originalTopic,
                                          int partition,
                                          long offset,
                                          String consumerGroup,
                                          Throwable failure,
                                          int attempt,
                                          JsonNode originalPayload,
                                          Instant failedAt) {
        String failureClass = failure == null ? "UnknownFailure" : failure.getClass().getName();
        String failureMessage = failure == null ? "unknown" : String.valueOf(failure.getMessage());
        return new DeadLetterEnvelope(originalTopic, partition, offset, consumerGroup,
                failureClass, failureMessage, digestOf(failure), failedAt, attempt, originalPayload);
    }

    /** Stable 64-hex digest of a stack trace; identical failures produce an identical digest. */
    public static String digestOf(Throwable failure) {
        if (failure == null) {
            return Hashes.sha256Hex("");
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            failure.printStackTrace(pw);
        }
        return Hashes.sha256Hex(sw.toString());
    }
}
