package com.laserpay.pdei.simulator.chaos;

import com.laserpay.pdei.common.domain.ChaosType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A chaos injection request, matching the body of {@code POST /sim/v1/chaos}
 * (platform contract 8.5): {@code {type: ChaosType, target: {...}, delayMs?, count?}}.
 *
 * <p>{@code target} is a free-form selector rather than a typed union because different chaos
 * types select entirely different things - an evidence id, a container name, a topic and offset,
 * a transaction. It is stored verbatim in {@code chaos_injections.target} (JSONB), so an
 * injection is reproducible from its record.
 *
 * <p>Recognised target keys, by type:
 * <pre>
 * DUPLICATE_EVENT, DELAYED_EVENT, OUT_OF_ORDER_EVENT, DROP_EVENT   runId
 * DELETE_EVIDENCE, CORRUPT_EVIDENCE_HASH, EXPIRE_EVIDENCE          evidenceId | transactionId
 * CONFLICTING_EVIDENCE                                             transactionId
 * KILL_WORKER, RESTART_CONSUMER, SLOW_CONSUMER                     service
 * REPLAY_EVENTS                                                    topic, fromOffset|fromTimestamp
 * INJECT_DISPUTE                                                   transactionId, merchantId, reasonCode
 * </pre>
 *
 * @param type    which failure to inject
 * @param target  selector, see above; never null after construction
 * @param delayMs delay in milliseconds for DELAYED_EVENT and SLOW_CONSUMER
 * @param count   how many events the injection applies to
 * @param actor   who asked, for the audit trail
 * @param runId   simulation run to act on; falls back to any active run when null
 */
public record ChaosRequest(ChaosType type,
                           Map<String, Object> target,
                           Long delayMs,
                           Integer count,
                           String actor,
                           String runId) {

    public ChaosRequest {
        // LinkedHashMap copy rather than Map.copyOf: this map is deserialised straight from a
        // JSON request body, where an explicit null is entirely legal, and Map.copyOf throws on
        // one. Insertion order is preserved so the stored target reads the way it was sent.
        target = target == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(target));
        actor = (actor == null || actor.isBlank()) ? "operator" : actor;
    }

    /** String value from the target selector, or null. */
    public String targetString(String key) {
        Object value = target.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Long targetLong(String key) {
        Object value = target.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** {@code count} with a floor of 1 and the configured ceiling applied by the caller. */
    public int countOrDefault(int fallback) {
        return count == null || count <= 0 ? fallback : count;
    }

    public long delayOrDefault(long fallbackMillis) {
        return delayMs == null || delayMs < 0 ? fallbackMillis : delayMs;
    }
}
