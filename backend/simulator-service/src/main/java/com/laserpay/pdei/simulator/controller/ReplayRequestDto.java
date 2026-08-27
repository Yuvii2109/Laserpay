package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.simulator.replay.ReplayRequest;

import java.time.Instant;

/**
 * Body of {@code POST /sim/v1/replay} (platform contract 8.5):
 * {@code {topic, fromOffset|fromTimestamp, merchantId?}}.
 *
 * <p>{@code maxRecords} and {@code republish} are extensions with safe defaults. Re-publication
 * is what actually proves replayability - the events go back onto the topic and every consumer
 * must converge on the same state - but an operator inspecting a suspicious offset range should
 * be able to look without touching anything, so it can be turned off.
 */
public record ReplayRequestDto(String topic,
                               Long fromOffset,
                               Instant fromTimestamp,
                               String merchantId,
                               Integer maxRecords,
                               Boolean republish) {

    /** Validation lives in {@link ReplayRequest}'s compact constructor. */
    public ReplayRequest toRequest() {
        return new ReplayRequest(topic, fromOffset, fromTimestamp, merchantId, maxRecords, republish);
    }
}
