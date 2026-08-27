package com.laserpay.pdei.api;

import com.laserpay.pdei.api.config.ApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PDEI api-gateway-service: the merchant-facing API (PLATFORM-CONTRACT.md section 8.1) on port 8080.
 *
 * <p>This module is the frontend's only backend and the read-only tool surface that the Python
 * ai-reasoning-service calls back into. It owns no domain logic: every route is thin orchestration
 * over evidence-core services and platform-persistence repositories.</p>
 *
 * <p>Scheduling is enabled for the WebSocket/SSE HEARTBEAT frame.</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ApiProperties.class)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
