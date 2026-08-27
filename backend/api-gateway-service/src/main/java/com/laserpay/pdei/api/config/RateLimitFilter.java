package com.laserpay.pdei.api.config;

import com.laserpay.pdei.api.support.CorrelationIds;
import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.json.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-merchant fixed-window API rate limit on the contract section 12 key
 * {@code pdei:ratelimit:{merchantId}:{window}}.
 *
 * <p>The window id is the epoch-second bucket, so the key rotates on its own and only needs a TTL
 * slightly longer than one window. Counting is a single {@code INCR}; the {@code EXPIRE} is only
 * issued on the first hit of a window, which keeps the hot path to one round trip.</p>
 *
 * <p><strong>Fails open.</strong> If Redis is unreachable the request is allowed. This is the
 * opposite choice from {@code AdmissionController} in evidence-core, and deliberately so: refusing
 * an AI call costs nothing because the deterministic path is always available, whereas refusing a
 * merchant's dashboard request because a cache is down turns a cache outage into an API outage.
 * The limiter protects against accidental hammering, not against a determined attacker.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String KEY_PREFIX = "pdei:ratelimit:";
    private static final String MERCHANT_PARAM = "merchantId";
    private static final String MERCHANT_HEADER = "X-PDEI-Merchant-Id";
    private static final String ANONYMOUS = "anonymous";

    private final ApiProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;

    public RateLimitFilter(ApiProperties properties, ObjectProvider<StringRedisTemplate> redisTemplates) {
        this.properties = properties;
        this.redisTemplates = redisTemplates;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        ApiProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        List<String> exempt = config.getExemptPathPrefixes();
        if (exempt == null || path == null) {
            return false;
        }
        return exempt.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ApiProperties.RateLimit config = properties.getRateLimit();
        String merchantId = resolveMerchantId(request);
        long windowSeconds = Math.max(1L, config.getWindow().toSeconds());
        long windowId = Instant.now().getEpochSecond() / windowSeconds;
        String key = KEY_PREFIX + merchantId + ":" + windowId;

        long count = increment(key, config.getWindow());
        if (count > config.getRequestsPerWindow()) {
            reject(response, merchantId, config, windowSeconds);
            return;
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(config.getRequestsPerWindow()));
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(Math.max(0L, config.getRequestsPerWindow() - count)));
        chain.doFilter(request, response);
    }

    /**
     * @return the request count in the current window, or 0 when Redis is unavailable (fail open)
     */
    private long increment(String key, Duration window) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return 0L;
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, window.plusSeconds(5));
            }
            return count == null ? 0L : count;
        } catch (RuntimeException e) {
            log.debug("Rate limit check skipped, Redis unavailable: {}", e.toString());
            return 0L;
        }
    }

    private void reject(HttpServletResponse response, String merchantId,
                        ApiProperties.RateLimit config, long windowSeconds) throws IOException {
        ErrorResponse body = new ErrorResponse(
                "RATE_LIMITED",
                "Rate limit exceeded: " + config.getRequestsPerWindow() + " requests per "
                        + windowSeconds + "s",
                CorrelationIds.current(),
                Instant.now(),
                Map.of("merchantId", merchantId, "limit", config.getRequestsPerWindow(),
                        "windowSeconds", windowSeconds));
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(windowSeconds));
        response.getWriter().write(Json.write(body));
    }

    private static String resolveMerchantId(HttpServletRequest request) {
        String fromParam = request.getParameter(MERCHANT_PARAM);
        if (fromParam != null && !fromParam.isBlank()) {
            return fromParam.trim();
        }
        String fromHeader = request.getHeader(MERCHANT_HEADER);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        return ANONYMOUS;
    }
}
