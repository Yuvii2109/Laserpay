package com.laserpay.pdei.orchestrator.model;

import com.laserpay.pdei.common.domain.CaseStatus;
import com.laserpay.pdei.common.event.EventType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Argument of activity {@code publishCaseEvent} - the workflow's one way to make its progress
 * visible outside Temporal.
 *
 * <p>It does two things at once, deliberately:</p>
 * <ol>
 *   <li>persists {@code status} and {@code progressPercent} on the {@code dispute_cases} row, so the
 *       case queue and the Case X-Ray screen track the workflow without querying Temporal;</li>
 *   <li>publishes a {@code CanonicalEvent} of {@code eventType} to {@code pdei.case.events.v1}.</li>
 * </ol>
 *
 * <p>Either half may be omitted. A null {@code eventType} means "persist the status only" - which is
 * what the phases that have no CASE event type in contract section 3.1 (AWAITING_EVIDENCE,
 * AWAITING_APPROVAL) use. A null {@code status} means "publish only, do not touch the row".</p>
 *
 * <p>{@code idempotencyKey} is derived deterministically by the workflow. The activity turns it into
 * the event's {@code eventId} as well, so a Temporal activity retry republishes an event with the
 * SAME id and every downstream consumer deduplicates it for free.</p>
 */
public record CaseEventCommand(
        String caseId,
        String merchantId,
        EventType eventType,
        CaseStatus status,
        CasePhase phase,
        int progressPercent,
        Map<String, Object> payload,
        String idempotencyKey,
        String correlationId,
        String causationId) {

    public CaseEventCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** Persist the phase and status on the case row without emitting a Kafka event. */
    public static CaseEventCommand statusOnly(CaseRef ref, CaseStatus status, CasePhase phase,
                                              String idempotencyKey) {
        return new CaseEventCommand(ref.caseId(), ref.merchantId(), null, status, phase,
                phase == null ? 0 : phase.percent(), Map.of(), idempotencyKey, ref.correlationId(),
                null);
    }

    /** Persist the phase and status and emit the matching CASE event. */
    public static CaseEventCommand of(CaseRef ref, EventType eventType, CaseStatus status,
                                      CasePhase phase, Map<String, Object> payload,
                                      String idempotencyKey) {
        return new CaseEventCommand(ref.caseId(), ref.merchantId(), eventType, status, phase,
                phase == null ? 0 : phase.percent(), payload, idempotencyKey, ref.correlationId(),
                null);
    }

    /** Mutable-friendly payload builder for call sites that assemble a few keys. */
    public static Map<String, Object> payloadOf(Object... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length == 0) {
            return Map.of();
        }
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("payloadOf requires an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                map.put(String.valueOf(keyValuePairs[i]), value);
            }
        }
        return map;
    }
}
