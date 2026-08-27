package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The fan-out point for every live subscriber: WebSocket control-tower sessions and both SSE
 * streams.
 *
 * <h2>Routing</h2>
 * <p>Every subscription is scoped to one merchant, and a frame only reaches subscribers of the
 * merchant it names. That is a correctness property, not an optimisation: this is a multi-tenant
 * financial surface and a mis-routed frame would leak one merchant's dispute activity to another.
 * The single exception is a merchant-less HEARTBEAT, which carries no data and goes to everyone.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Frames arrive on Kafka listener threads and on the scheduler thread while subscribers connect
 * and disconnect on request threads, so every collection here is concurrent. WebSocket sessions are
 * wrapped by the handler in a {@code ConcurrentWebSocketSessionDecorator} before registration:
 * {@code WebSocketSession.sendMessage} is not thread safe, and two listener threads writing to one
 * session interleave into a corrupt frame.</p>
 *
 * <h2>Failure</h2>
 * <p>A send failure means one subscriber is gone; it never propagates. The subscriber is dropped and
 * the loop continues, because a browser closing a tab must not stop the other subscribers receiving
 * the frame, and it certainly must not fail the Kafka listener and cause a redelivery.</p>
 */
@Component
public class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    /** SSE event name for a frame; the browser's EventSource listens on this. */
    private static final String SSE_EVENT_NAME = "frame";

    private final ApiProperties properties;
    private final Clocks clock;

    /** merchantId to live WebSocket sessions. */
    private final Map<String, Set<WebSocketSession>> webSocketSessions = new ConcurrentHashMap<>();

    /** merchantId to live SSE emitters of the canonical event tail. */
    private final Map<String, Set<SseEmitter>> eventEmitters = new ConcurrentHashMap<>();

    /** caseId to live SSE emitters of case progress. */
    private final Map<String, Set<SseEmitter>> caseEmitters = new ConcurrentHashMap<>();

    private final AtomicLong framesDelivered = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();

    public StreamHub(ApiProperties properties, Clocks clock) {
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------
    // Subscription
    // ---------------------------------------------------------------------------------------

    /** Register a control-tower WebSocket session. The session must already be concurrency safe. */
    public void registerWebSocket(String merchantId, WebSocketSession session) {
        requireMerchantId(merchantId);
        Set<WebSocketSession> sessions = webSocketSessions
                .computeIfAbsent(merchantId, key -> ConcurrentHashMap.newKeySet());
        enforceLimit(sessions.size(), merchantId);
        sessions.add(session);
        log.debug("WebSocket subscribed: merchant={} sessions={}", merchantId, sessions.size());
    }

    public void unregisterWebSocket(String merchantId, WebSocketSession session) {
        if (merchantId == null) {
            webSocketSessions.values().forEach(sessions -> sessions.remove(session));
            return;
        }
        Set<WebSocketSession> sessions = webSocketSessions.get(merchantId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                webSocketSessions.remove(merchantId, sessions);
            }
        }
    }

    /** {@code SSE /api/v1/stream/events?merchantId=}: the canonical event tail. */
    public SseEmitter subscribeToEvents(String merchantId) {
        requireMerchantId(merchantId);
        Set<SseEmitter> emitters = eventEmitters
                .computeIfAbsent(merchantId, key -> ConcurrentHashMap.newKeySet());
        enforceLimit(emitters.size(), merchantId);
        return register(emitters, merchantId, "events");
    }

    /** {@code SSE /api/v1/stream/cases/{caseId}}: progress of one case. */
    public SseEmitter subscribeToCase(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw ValidationException.field("caseId", "is required");
        }
        Set<SseEmitter> emitters = caseEmitters
                .computeIfAbsent(caseId, key -> ConcurrentHashMap.newKeySet());
        return register(emitters, caseId, "case");
    }

    private SseEmitter register(Set<SseEmitter> emitters, String key, String kind) {
        SseEmitter emitter = new SseEmitter(properties.getStream().getSseTimeout().toMillis());
        emitters.add(emitter);
        // All three terminal callbacks remove the emitter. Without the timeout and error hooks a
        // browser that disappears without closing cleanly would leave the emitter in the map
        // forever, and the map would grow for the life of the process.
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        try {
            // An immediate frame flushes response headers so the browser's EventSource fires onopen
            // instead of waiting for the first real event, which on a quiet merchant could be minutes.
            emitter.send(SseEmitter.event()
                    .name(SSE_EVENT_NAME)
                    .data(Json.write(StreamFrame.heartbeat(clock.now())), org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
        log.debug("SSE subscribed: kind={} key={} subscribers={}", kind, key, emitters.size());
        return emitter;
    }

    // ---------------------------------------------------------------------------------------
    // Delivery
    // ---------------------------------------------------------------------------------------

    /**
     * Deliver a frame to every subscriber of its merchant.
     *
     * @return the number of subscribers the frame actually reached
     */
    public int broadcast(StreamFrame frame) {
        if (frame == null) {
            return 0;
        }
        String payload = Json.write(frame);
        int delivered = 0;

        if (frame.merchantId() == null) {
            for (Map.Entry<String, Set<WebSocketSession>> entry : webSocketSessions.entrySet()) {
                delivered += sendWebSocket(entry.getKey(), entry.getValue(), payload);
            }
            for (Map.Entry<String, Set<SseEmitter>> entry : eventEmitters.entrySet()) {
                delivered += sendSse(entry.getValue(), payload);
            }
        } else {
            delivered += sendWebSocket(frame.merchantId(),
                    webSocketSessions.get(frame.merchantId()), payload);
            delivered += sendSse(eventEmitters.get(frame.merchantId()), payload);
        }

        // A case frame also reaches anyone watching that specific case.
        Object caseId = frame.data().get("caseId");
        if (caseId instanceof String id && !id.isBlank()) {
            delivered += sendSse(caseEmitters.get(id), payload);
        }
        framesDelivered.addAndGet(delivered);
        return delivered;
    }

    /** Deliver a frame only to the subscribers of one case. */
    public int broadcastToCase(String caseId, StreamFrame frame) {
        if (caseId == null || frame == null) {
            return 0;
        }
        int delivered = sendSse(caseEmitters.get(caseId), Json.write(frame));
        framesDelivered.addAndGet(delivered);
        return delivered;
    }

    /**
     * The scheduled HEARTBEAT frame.
     *
     * <p>It exists so an idle connection is not silently reaped. Proxies and load balancers close
     * connections that have been quiet for a minute or two, and a merchant with no dispute activity
     * is exactly the case where the control tower socket would otherwise go quiet and die, then
     * appear to the operator to be live while receiving nothing.</p>
     *
     * <p>Scheduled by {@link StreamSchedulingConfig} rather than by {@code @Scheduled}, because the
     * interval is a typed {@code Duration} property and {@code fixedDelayString} only understands
     * plain milliseconds or ISO-8601 text. Registering the task programmatically means
     * {@code pdei.api.stream.heartbeat-interval: 15s} works, which is what a reader expects.</p>
     */
    public void heartbeat() {
        if (webSocketSessions.isEmpty() && eventEmitters.isEmpty() && caseEmitters.isEmpty()) {
            return;
        }
        java.time.Instant now = clock.now();
        webSocketSessions.forEach((merchantId, sessions) ->
                sendWebSocket(merchantId, sessions,
                        Json.write(StreamFrame.heartbeat(now, merchantId, sessions.size()))));
        eventEmitters.forEach((merchantId, emitters) ->
                sendSse(emitters, Json.write(StreamFrame.heartbeat(now, merchantId, emitters.size()))));
        caseEmitters.forEach((caseId, emitters) ->
                sendSse(emitters, Json.write(StreamFrame.heartbeat(now))));
    }

    private int sendWebSocket(String merchantId, Set<WebSocketSession> sessions, String payload) {
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
                delivered++;
            } catch (IOException | RuntimeException e) {
                framesDropped.incrementAndGet();
                sessions.remove(session);
                log.debug("Dropping WebSocket session for merchant {}: {}", merchantId, e.toString());
                closeQuietly(session);
            }
        }
        if (sessions.isEmpty()) {
            webSocketSessions.remove(merchantId, sessions);
        }
        return delivered;
    }

    private int sendSse(Set<SseEmitter> emitters, String payload) {
        if (emitters == null || emitters.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(SSE_EVENT_NAME)
                        .data(payload, org.springframework.http.MediaType.APPLICATION_JSON));
                delivered++;
            } catch (IOException | RuntimeException e) {
                // RuntimeException covers IllegalStateException, which is what an already completed
                // or timed-out emitter throws; both mean the same thing here: this one is gone.
                framesDropped.incrementAndGet();
                emitters.remove(emitter);
                try {
                    emitter.completeWithError(e);
                } catch (RuntimeException ignored) {
                    // Already completed by the container; nothing left to do.
                }
            }
        }
        return delivered;
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException | RuntimeException ignored) {
            // The session is already unusable; that is why we are here.
        }
    }

    private void enforceLimit(int current, String merchantId) {
        int max = properties.getStream().getMaxSessionsPerMerchant();
        if (max > 0 && current >= max) {
            throw new ValidationException(
                    "too many open stream subscriptions for this merchant",
                    Map.of("merchantId", merchantId, "max", max));
        }
    }

    private static void requireMerchantId(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw ValidationException.field("merchantId", "is required to subscribe to a stream");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Introspection, used by the health route and tests
    // ---------------------------------------------------------------------------------------

    public int webSocketSubscribers(String merchantId) {
        Set<WebSocketSession> sessions = webSocketSessions.get(merchantId);
        return sessions == null ? 0 : sessions.size();
    }

    public int eventSubscribers(String merchantId) {
        Set<SseEmitter> emitters = eventEmitters.get(merchantId);
        return emitters == null ? 0 : emitters.size();
    }

    public int caseSubscribers(String caseId) {
        Set<SseEmitter> emitters = caseEmitters.get(caseId);
        return emitters == null ? 0 : emitters.size();
    }

    public long framesDelivered() {
        return framesDelivered.get();
    }

    public long framesDropped() {
        return framesDropped.get();
    }
}
