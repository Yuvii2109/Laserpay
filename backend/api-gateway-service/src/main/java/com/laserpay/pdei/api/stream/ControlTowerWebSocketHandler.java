package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.common.time.Clocks;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code WS /ws/control-tower?merchantId=...} (PLATFORM-CONTRACT.md section 8.1).
 *
 * <p>Server to client push only. The socket is deliberately not a command channel: anything a client
 * sends is answered with a HEARTBEAT and otherwise ignored. Financial state changes go through the
 * REST routes where they are validated, authorised and audited, and adding a second, unaudited way
 * to reach the domain through a socket frame would undo all of that.</p>
 *
 * <p>Every raw session is wrapped in a {@link ConcurrentWebSocketSessionDecorator} before it reaches
 * {@link StreamHub}. Frames are pushed from Kafka listener threads and from the heartbeat scheduler
 * concurrently, and {@code WebSocketSession.sendMessage} is not thread safe: two threads writing at
 * once interleave into a frame no client can parse. The decorator also bounds the send buffer, so a
 * client that stops reading is closed rather than growing the heap.</p>
 */
public class ControlTowerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ControlTowerWebSocketHandler.class);

    /** The query parameter that scopes the subscription. Mandatory. */
    public static final String MERCHANT_PARAM = "merchantId";

    /** Bounded per-session send buffer; a client slower than this is disconnected, not buffered. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /** Raw session id to the merchant it subscribed as, so close can unregister precisely. */
    private final Map<String, String> merchantBySession = new ConcurrentHashMap<>();

    /** Raw session id to the decorated session that was actually registered. */
    private final Map<String, WebSocketSession> decoratedBySession = new ConcurrentHashMap<>();

    private final StreamHub hub;
    private final ApiProperties properties;
    private final Clocks clock;

    public ControlTowerWebSocketHandler(StreamHub hub, ApiProperties properties, Clocks clock) {
        this.hub = hub;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String merchantId = merchantIdOf(session);
        if (merchantId == null) {
            // Closing with a policy violation rather than accepting an unscoped session: a socket
            // with no merchant would either receive nothing or, worse, receive everything.
            log.debug("Rejecting control-tower socket with no {} parameter", MERCHANT_PARAM);
            session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(),
                    MERCHANT_PARAM + " query parameter is required"));
            return;
        }
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES);
        try {
            hub.registerWebSocket(merchantId, concurrent);
        } catch (RuntimeException e) {
            log.debug("Refusing control-tower socket for {}: {}", merchantId, e.toString());
            session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), e.getMessage()));
            return;
        }
        merchantBySession.put(session.getId(), merchantId);
        decoratedBySession.put(session.getId(), concurrent);
        concurrent.sendMessage(new TextMessage(Json.write(
                StreamFrame.heartbeat(clock.now(), merchantId, hub.webSocketSubscribers(merchantId)))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // The only inbound message with a meaning is a client-side keepalive. Answering it with a
        // HEARTBEAT lets a client measure round-trip latency without opening a second channel.
        WebSocketSession target = decoratedBySession.getOrDefault(session.getId(), session);
        String merchantId = merchantBySession.get(session.getId());
        if (target.isOpen()) {
            target.sendMessage(new TextMessage(Json.write(
                    StreamFrame.heartbeat(clock.now(), merchantId,
                            merchantId == null ? 0 : hub.webSocketSubscribers(merchantId)))));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String merchantId = merchantBySession.remove(session.getId());
        WebSocketSession decorated = decoratedBySession.remove(session.getId());
        hub.unregisterWebSocket(merchantId, decorated == null ? session : decorated);
        log.debug("Control-tower socket closed: merchant={} status={}", merchantId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Control-tower socket transport error: {}", exception.toString());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    /** Sessions are never resumed across a transport error, so partial messages are meaningless. */
    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private static String merchantIdOf(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        String merchantId = UriComponentsBuilder.fromUri(uri).build()
                .getQueryParams().getFirst(MERCHANT_PARAM);
        return merchantId == null || merchantId.isBlank() ? null : merchantId.trim();
    }

    /** Exposed so the configuration can report the limit it enforces. */
    public ApiProperties properties() {
        return properties;
    }
}
