package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.common.domain.ChaosType;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.simulator.chaos.ChaosRequest;

import java.util.Locale;
import java.util.Map;

/**
 * Body of {@code POST /sim/v1/chaos} (platform contract 8.5):
 * {@code {type: ChaosType, target: {...}, delayMs?, count?}}.
 *
 * <p>{@code type} is parsed strictly - an unknown value is a 400, not a silent no-op. Injecting
 * the wrong failure because a name was misspelled would be worse than injecting none.
 *
 * @param type    a {@link ChaosType} name
 * @param target  free-form selector; see {@link ChaosRequest} for the recognised keys per type
 * @param delayMs delay for DELAYED_EVENT and SLOW_CONSUMER
 * @param count   how many events the injection applies to
 * @param runId   run to act on; falls back to any run currently in flight
 * @param actor   who asked
 */
public record ChaosRequestDto(String type,
                              Map<String, Object> target,
                              Long delayMs,
                              Integer count,
                              String runId,
                              String actor) {

    public ChaosRequest toRequest() {
        return new ChaosRequest(parseType(type), target, delayMs, count, actor, runId);
    }

    private static ChaosType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("type is required and must be a ChaosType");
        }
        try {
            return ChaosType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown ChaosType: " + raw
                    + "; expected one of " + java.util.Arrays.toString(ChaosType.values()));
        }
    }
}
