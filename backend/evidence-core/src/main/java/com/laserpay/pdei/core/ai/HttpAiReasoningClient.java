package com.laserpay.pdei.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.laserpay.pdei.common.json.Json;
import com.laserpay.pdei.core.model.InvestigationContext;
import com.laserpay.pdei.core.model.InvestigationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * HTTP client for the Python {@code ai-reasoning-service} (platform contract 8.6).
 *
 * <p>Everything about talking to a model that could hurt the platform is handled here:</p>
 * <ul>
 *   <li><b>Timeouts</b> - a slow model must never hold a Temporal activity thread open.</li>
 *   <li><b>Bounded retries with exponential backoff</b> - transient failures are retried, and only
 *       transient ones: a 4xx means the request is wrong and retrying cannot fix it.</li>
 *   <li><b>Circuit breaker</b> - after repeated failures calls are refused immediately.</li>
 *   <li><b>Deterministic fallback</b> - when the circuit is open or all attempts fail, the platform
 *       answers from {@link DeterministicInvestigator} instead of failing the case. Dispute handling
 *       continues without AI; it just gets less nuanced.</li>
 * </ul>
 *
 * <p>The service token goes in {@code X-PDEI-Service-Token}, matching the header the AI service uses
 * when it calls back into the read-only {@code /api/v1/ai-tools/*} endpoints.</p>
 */
public class HttpAiReasoningClient implements AiReasoningClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiReasoningClient.class);

    public static final String SERVICE_TOKEN_HEADER = "X-PDEI-Service-Token";
    private static final String METRIC_REQUESTS = "pdei_ai_requests_total";
    private static final String METRIC_LATENCY = "pdei_ai_latency_seconds";
    private static final String PROVIDER_TAG = "ai-reasoning-service";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final DeterministicInvestigator fallback;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double backoffMultiplier;

    public HttpAiReasoningClient(String baseUrl, String serviceToken, Duration connectTimeout,
                                 Duration readTimeout, int maxAttempts, Duration initialBackoff,
                                 double backoffMultiplier, CircuitBreaker circuitBreaker,
                                 DeterministicInvestigator fallback, MeterRegistry meterRegistry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) (connectTimeout == null ? 2000L : connectTimeout.toMillis()));
        factory.setReadTimeout((int) (readTimeout == null ? 30000L : readTimeout.toMillis()));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl == null ? "http://ai-reasoning-service:8000" : baseUrl)
                .requestFactory(factory)
                .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken == null ? "" : serviceToken)
                .messageConverters(converters -> {
                    converters.removeIf(converter -> converter instanceof MappingJackson2HttpMessageConverter);
                    converters.add(new MappingJackson2HttpMessageConverter(Json.mapper()));
                })
                .build();
        this.circuitBreaker = circuitBreaker == null
                ? new CircuitBreaker(5, Duration.ofSeconds(60)) : circuitBreaker;
        this.fallback = fallback == null ? new DeterministicInvestigator() : fallback;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff == null ? Duration.ofMillis(500) : initialBackoff;
        this.backoffMultiplier = backoffMultiplier <= 1.0d ? 2.0d : backoffMultiplier;
    }

    @Override
    public InvestigationResult investigate(InvestigationContext context) {
        long startNanos = System.nanoTime();
        if (!circuitBreaker.allowRequest()) {
            count("circuit_open");
            log.warn("AI circuit is open; answering investigation {} deterministically",
                    context.investigationId());
            return fallback.investigate(context, 0, elapsedMillis(startNanos));
        }
        try {
            InvestigationResult result = callWithRetry("investigate", () -> restClient.post()
                    .uri("/v1/investigate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(context)
                    .retrieve()
                    .body(InvestigationResult.class));
            if (result == null) {
                circuitBreaker.recordFailure();
                count("empty_response");
                return fallback.investigate(context, maxAttempts, elapsedMillis(startNanos));
            }
            circuitBreaker.recordSuccess();
            count("success");
            recordLatency(startNanos);
            // The service may omit the investigation id; the platform id is authoritative.
            return result.investigationId() == null
                    ? new InvestigationResult(context.investigationId(), result.classification(),
                            result.confidence(), result.supportingEvidence(), result.missingEvidence(),
                            result.contradictions(), result.reasoningSummary(), result.narrative(),
                            result.recommendedAction(), result.citations(), result.modelMetadata())
                    : result;
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure();
            count("failure");
            log.warn("AI investigation {} failed after {} attempt(s), falling back to the deterministic"
                    + " path: {}", context.investigationId(), maxAttempts, e.toString());
            return fallback.investigate(context, maxAttempts, elapsedMillis(startNanos));
        }
    }

    @Override
    public String narrative(InvestigationContext context) {
        if (!circuitBreaker.allowRequest()) {
            return fallback.narrative(context, null, context.evidence().stream()
                    .map(view -> view.evidenceId()).toList());
        }
        try {
            JsonNode response = callWithRetry("narrative", () -> restClient.post()
                    .uri("/v1/narrative")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(context)
                    .retrieve()
                    .body(JsonNode.class));
            circuitBreaker.recordSuccess();
            if (response == null) {
                return fallbackNarrative(context);
            }
            if (response.hasNonNull("narrative")) {
                return response.get("narrative").asText();
            }
            return response.isTextual() ? response.asText() : fallbackNarrative(context);
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure();
            log.warn("AI narrative for {} failed, using the deterministic narrative: {}",
                    context.investigationId(), e.toString());
            return fallbackNarrative(context);
        }
    }

    @Override
    public AdmissionScore admissionScore(InvestigationContext context) {
        try {
            AdmissionScore score = callWithRetry("admission", () -> restClient.post()
                    .uri("/v1/admission/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(context)
                    .retrieve()
                    .body(AdmissionScore.class));
            return score == null ? new AdmissionScore(false, 0, "no response") : score;
        } catch (RuntimeException e) {
            // Advisory only: the Java AdmissionController owns the real decision.
            return new AdmissionScore(false, 0, "ai admission scoring unavailable: " + e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!circuitBreaker.allowRequest()) {
            return false;
        }
        try {
            restClient.get().uri("/ready").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Bounded retry with exponential backoff. Client errors are not retried: a 4xx means the request
     * itself is wrong, and repeating it only wastes the budget.
     */
    private <T> T callWithRetry(String operation, Supplier<T> call) {
        RuntimeException last = null;
        long backoffMillis = initialBackoff.toMillis();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                log.warn("AI {} rejected the request ({}); not retrying", operation, e.getStatusCode());
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleep(backoffMillis);
                    backoffMillis = (long) (backoffMillis * backoffMultiplier);
                }
            }
        }
        throw last == null ? new IllegalStateException("AI " + operation + " failed") : last;
    }

    private String fallbackNarrative(InvestigationContext context) {
        return fallback.narrative(context, null,
                context.evidence().stream().map(view -> view.evidenceId()).toList());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private void count(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(METRIC_REQUESTS, "provider", PROVIDER_TAG, "outcome", outcome).increment();
        } catch (RuntimeException e) {
            // metrics never break the AI path
        }
    }

    private void recordLatency(long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Timer.builder(METRIC_LATENCY)
                    .tag("provider", PROVIDER_TAG)
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // metrics never break the AI path
        }
    }
}
