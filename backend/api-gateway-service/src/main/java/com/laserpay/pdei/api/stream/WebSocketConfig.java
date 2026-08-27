package com.laserpay.pdei.api.stream;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.common.time.Clocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the control-tower socket at {@code /ws/control-tower} (contract section 8.1).
 *
 * <p>Plain WebSocket, not STOMP and not SockJS. The traffic is one-directional server push of small
 * JSON frames to a browser that already speaks native WebSocket, so a broker protocol on top would
 * add a message broker's worth of machinery for nothing, and SockJS fallbacks would add three
 * transports nobody needs. The frontend hook talks to this with the browser's own
 * {@code WebSocket}.</p>
 *
 * <p>Allowed origins mirror the CORS configuration exactly. A WebSocket handshake is not covered by
 * the CORS filter, so leaving this open would leave a hole beside a locked door.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Contract section 8.1 path; NEXT_PUBLIC_WS_URL points here. */
    public static final String PATH = "/ws/control-tower";

    private final ControlTowerWebSocketHandler handler;
    private final ApiProperties properties;

    /**
     * The handler is built here and exposed as a bean, rather than injected, so that this class can
     * both register it with the registry and publish it for anything else that wants it without a
     * cycle between the two.
     */
    public WebSocketConfig(StreamHub hub, ApiProperties properties, Clocks clock) {
        this.properties = properties;
        this.handler = new ControlTowerWebSocketHandler(hub, properties, clock);
    }

    @Bean
    public ControlTowerWebSocketHandler controlTowerWebSocketHandler() {
        return handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH)
                .setAllowedOrigins(properties.getCors().getAllowedOrigins().toArray(String[]::new));
    }
}
