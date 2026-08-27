package com.laserpay.pdei.api.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway configuration, prefix {@code pdei.api}.
 *
 * <p>Every default is the contract value, so the service behaves as specified with an empty
 * application.yml. The environment variable each property binds to is named in
 * {@code src/main/resources/application.yml} (PLATFORM-CONTRACT.md section 15).</p>
 */
@ConfigurationProperties(prefix = "pdei.api")
public class ApiProperties {

    /** Shared secret the AI service must present in X-PDEI-Service-Token (PDEI_SERVICE_TOKEN). */
    private String serviceToken = "dev-service-token";

    private final Cors cors = new Cors();
    private final RateLimit rateLimit = new RateLimit();
    private final Stream stream = new Stream();
    private final Orchestrator orchestrator = new Orchestrator();
    private final Paging paging = new Paging();

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public Cors getCors() {
        return cors;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Stream getStream() {
        return stream;
    }

    public Orchestrator getOrchestrator() {
        return orchestrator;
    }

    public Paging getPaging() {
        return paging;
    }

    /** CORS for the Next.js dev server (contract section 2: frontend on port 3000). */
    public static class Cors {

        private List<String> allowedOrigins = List.of("http://localhost:3000");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private boolean allowCredentials = true;
        private Duration maxAge = Duration.ofHours(1);

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
    }

    /** Fixed-window API rate limit on the Redis key pdei:ratelimit:{merchantId}:{window}. */
    public static class RateLimit {

        private boolean enabled = true;

        /** Requests allowed per merchant per window. */
        private int requestsPerWindow = 600;

        private Duration window = Duration.ofMinutes(1);

        /** Paths never rate limited: ops surfaces, streams and the AI callback surface. */
        private List<String> exemptPathPrefixes =
                List.of("/actuator", "/ws", "/api/v1/ai-tools", "/swagger-ui", "/v3/api-docs");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerWindow() {
            return requestsPerWindow;
        }

        public void setRequestsPerWindow(int requestsPerWindow) {
            this.requestsPerWindow = requestsPerWindow;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public List<String> getExemptPathPrefixes() {
            return exemptPathPrefixes;
        }

        public void setExemptPathPrefixes(List<String> exemptPathPrefixes) {
            this.exemptPathPrefixes = exemptPathPrefixes;
        }
    }

    /** WebSocket and SSE fan-out. */
    public static class Stream {

        /**
         * Whether the control-tower Kafka consumer runs. False turns the gateway into a pure REST
         * service with no live frames, which is how it runs where no broker exists.
         */
        private boolean kafkaEnabled = true;

        private Duration heartbeatInterval = Duration.ofSeconds(15);

        /** SSE emitter timeout; the browser reconnects automatically when it fires. */
        private Duration sseTimeout = Duration.ofMinutes(30);

        /** Guard against a runaway client opening unbounded sessions. */
        private int maxSessionsPerMerchant = 50;

        /** TTL of the Kafka dedupe marker (contract section 12: pdei:idem:{eventId}, TTL 7d). */
        private Duration dedupeTtl = Duration.ofDays(7);

        /** Bounded in-memory dedupe used when Redis is unreachable. */
        private int localDedupeCapacity = 20000;

        public boolean isKafkaEnabled() {
            return kafkaEnabled;
        }

        public void setKafkaEnabled(boolean kafkaEnabled) {
            this.kafkaEnabled = kafkaEnabled;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getSseTimeout() {
            return sseTimeout;
        }

        public void setSseTimeout(Duration sseTimeout) {
            this.sseTimeout = sseTimeout;
        }

        public int getMaxSessionsPerMerchant() {
            return maxSessionsPerMerchant;
        }

        public void setMaxSessionsPerMerchant(int maxSessionsPerMerchant) {
            this.maxSessionsPerMerchant = maxSessionsPerMerchant;
        }

        public Duration getDedupeTtl() {
            return dedupeTtl;
        }

        public void setDedupeTtl(Duration dedupeTtl) {
            this.dedupeTtl = dedupeTtl;
        }

        public int getLocalDedupeCapacity() {
            return localDedupeCapacity;
        }

        public void setLocalDedupeCapacity(int localDedupeCapacity) {
            this.localDedupeCapacity = localDedupeCapacity;
        }
    }

    /** Where human case decisions are signalled: case-orchestrator-service, contract section 10. */
    public static class Orchestrator {

        /** When false the gateway always takes the deterministic local-transition fallback. */
        private boolean enabled = true;

        private String baseUrl = "http://case-orchestrator-service:8085";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /** Defaults for the page / size query parameters. */
    public static class Paging {

        private int defaultSize = 25;
        private int maxSize = 200;

        public int getDefaultSize() {
            return defaultSize;
        }

        public void setDefaultSize(int defaultSize) {
            this.defaultSize = defaultSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }
}
