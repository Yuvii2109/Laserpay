package com.laserpay.pdei.simulator.chaos;

import com.laserpay.pdei.common.domain.ChaosType;
import com.laserpay.pdei.persistence.entity.ChaosInjectionEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of one chaos injection: what was done, to what, and whether it worked.
 *
 * <p>Recorded to {@code chaos_injections} and returned by {@code POST /sim/v1/chaos}. The
 * {@code detail} map carries whatever the specific injection can prove about itself - the
 * evidence id whose hash was corrupted, the number of events re-published, the container that was
 * killed. That is what makes a chaos demo a demonstration rather than an assertion: the record
 * says exactly which failure was injected and when, and the platform's behaviour afterwards is
 * observable against it.
 *
 * @param injectionId the {@code chaos_injections} row id
 * @param type        what was injected
 * @param status      one of the {@code ChaosInjectionEntity.STATUS_*} values
 * @param mode        how it was applied, e.g. {@code DOCKER_API} or {@code REDIS_CONTROL_DIRECTIVE}
 * @param summary     one human-readable line
 * @param detail      injection-specific evidence
 * @param at          when it was applied
 * @param errorMessage failure detail, null on success
 */
public record ChaosResult(String injectionId,
                          ChaosType type,
                          String status,
                          String mode,
                          String summary,
                          Map<String, Object> detail,
                          Instant at,
                          String errorMessage) {

    /** How an infrastructure-level injection reached its target. */
    public static final String MODE_DOCKER_API = "DOCKER_API";
    /**
     * The documented fallback when container control is unavailable: the directive is written to
     * {@code pdei:sim:control:{service}} in Redis for the target service to honour.
     */
    public static final String MODE_REDIS_CONTROL_DIRECTIVE = "REDIS_CONTROL_DIRECTIVE";
    /** Applied inside this service, against a running emission or the database. */
    public static final String MODE_IN_PROCESS = "IN_PROCESS";

    public ChaosResult {
        // Defensive copy via LinkedHashMap, not Map.copyOf: detail entries legitimately carry
        // null values (an evidence row with no objectKey, a run that was not named), and
        // Map.copyOf throws on those. Insertion order is also worth keeping - this map is read
        // by a human in the chaos history.
        detail = detail == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public boolean isApplied() {
        return ChaosInjectionEntity.STATUS_APPLIED.equals(status);
    }

    public static ChaosResult applied(String injectionId, ChaosType type, String mode,
                                      String summary, Map<String, Object> detail, Instant at) {
        return new ChaosResult(injectionId, type, ChaosInjectionEntity.STATUS_APPLIED, mode,
                summary, detail, at, null);
    }

    public static ChaosResult failed(String injectionId, ChaosType type, String summary,
                                     String errorMessage, Instant at) {
        return new ChaosResult(injectionId, type, ChaosInjectionEntity.STATUS_FAILED,
                MODE_IN_PROCESS, summary, Map.of(), at, errorMessage);
    }

    /** Mutable detail accumulator for the engine's dispatch methods. */
    public static Map<String, Object> detailMap() {
        return new LinkedHashMap<>();
    }
}
