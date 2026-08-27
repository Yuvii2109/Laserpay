package com.laserpay.pdei.simulator.chaos;

import com.laserpay.pdei.common.event.ActorType;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.core.audit.AuditCommand;
import com.laserpay.pdei.core.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the {@code CHAOS_INJECTED} notification the simulation console renders.
 *
 * <h2>How this reaches the UI</h2>
 * The WebSocket frame the frontend consumes is
 * {@code {type, at, merchantId, data}} with {@code type = "CHAOS_INJECTED"}
 * (platform contract 8.1), and api-gateway-service is the component that pushes it. The
 * simulator therefore does not open a socket of its own; it records the injection as a
 * hash-chained audit event on {@code pdei.audit.events.v1} - a topic every service already
 * produces to and audit-service already consumes - with {@code action = CHAOS_INJECTED} and the
 * frame's {@code data} object as the audit {@code after} value. api-gateway wraps that into the
 * frame.
 *
 * <p>Two things fall out of that choice. Chaos becomes part of the permanent, tamper-evident
 * record rather than a transient UI event, which matters because "which failure was injected, and
 * when" is exactly what an observer needs to trust a recovery demo. And no new topic, key
 * namespace or endpoint is invented for it.
 *
 * <p>{@link AuditRecorder} is optional here. It needs the evidence-core JDBC ports, and the
 * simulator must still be able to inject chaos in an environment without them - the injection is
 * always persisted to {@code chaos_injections} regardless, so nothing is lost but the chained
 * copy.
 */
@Component
public class ChaosNotifier {

    /** WebSocket frame type and audit action. Spelled exactly as platform contract 8.1 has it. */
    public static final String NOTIFICATION_TYPE = "CHAOS_INJECTED";
    public static final String ENTITY_TYPE = "CHAOS_INJECTION";

    private static final Logger log = LoggerFactory.getLogger(ChaosNotifier.class);

    private final ObjectProvider<AuditRecorder> auditRecorders;
    private final Clocks clock;

    public ChaosNotifier(ObjectProvider<AuditRecorder> auditRecorders, Clocks clock) {
        this.auditRecorders = auditRecorders;
        this.clock = clock;
    }

    /**
     * Records and announces one injection.
     *
     * @param request what was asked for
     * @param result  what happened
     * @return the notification data object, which is also the WebSocket frame's {@code data}
     */
    public Map<String, Object> notifyInjected(ChaosRequest request, ChaosResult result) {
        Map<String, Object> data = frameData(request, result);
        String merchantId = request.targetString("merchantId");

        AuditRecorder recorder = auditRecorders.getIfAvailable();
        if (recorder == null) {
            log.info("CHAOS_INJECTED {} ({}) - no AuditRecorder configured, chaos_injections row only",
                    result.type(), result.status());
            return data;
        }
        try {
            recorder.record(new AuditCommand(
                    ENTITY_TYPE,
                    result.injectionId(),
                    merchantId == null ? "SYSTEM" : merchantId,
                    NOTIFICATION_TYPE,
                    request.actor(),
                    ActorType.SIMULATOR,
                    null,
                    null,
                    data));
        } catch (RuntimeException e) {
            // The chaos_injections row is already committed; a missing audit link must not undo
            // an injection that really happened.
            log.warn("could not record CHAOS_INJECTED audit event for {}: {}",
                    result.injectionId(), e.toString());
        }
        return data;
    }

    /**
     * The {@code data} payload of the {@code CHAOS_INJECTED} frame. Insertion-ordered so the JSON
     * reads the same way every time it appears in a log or a UI panel.
     */
    private Map<String, Object> frameData(ChaosRequest request, ChaosResult result) {
        Instant at = result.at() == null ? clock.now() : result.at();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationType", NOTIFICATION_TYPE);
        data.put("injectionId", result.injectionId());
        data.put("chaosType", result.type().name());
        data.put("category", result.type().category().name());
        data.put("status", result.status());
        data.put("mode", result.mode());
        data.put("summary", result.summary());
        data.put("runId", request.runId());
        data.put("target", request.target());
        data.put("delayMs", request.delayMs());
        data.put("count", request.count());
        data.put("actor", request.actor());
        data.put("at", at.toString());
        data.put("detail", result.detail());
        data.put("errorMessage", result.errorMessage());
        return data;
    }
}
