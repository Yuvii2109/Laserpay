package com.laserpay.pdei.api.security;

import com.laserpay.pdei.api.config.ApiProperties;
import com.laserpay.pdei.api.support.CorrelationIds;
import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.json.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/v1/ai-tools/*} (PLATFORM-CONTRACT.md section 8.6 callback direction).
 *
 * <p>The Python ai-reasoning-service calls back into the gateway for facts. Those routes are
 * read-only by construction, but they still expose merchant data, so every request must carry
 * {@code X-PDEI-Service-Token} equal to {@code PDEI_SERVICE_TOKEN}. Anything else is 401.</p>
 *
 * <p>The comparison is constant time. A token check that short-circuits on the first differing byte
 * leaks the token one character at a time to anyone who can measure response latency, and this
 * filter is the only thing standing between the AI service surface and the network.</p>
 *
 * <p>Registered with an explicit URL pattern by {@code WebFilterConfig} rather than as a component,
 * so it can never accidentally apply to the merchant-facing routes.</p>
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);

    /** Header the AI service presents (contract section 8.6). */
    public static final String HEADER = "X-PDEI-Service-Token";

    /** The path prefix this filter guards. */
    public static final String GUARDED_PREFIX = "/api/v1/ai-tools";

    private final ApiProperties properties;

    public ServiceTokenFilter(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        String expected = properties.getServiceToken();

        if (expected == null || expected.isBlank()) {
            // A blank configured token would otherwise authenticate every caller that omits the
            // header. Refuse instead: a misconfigured gateway must not become an open one.
            log.error("PDEI_SERVICE_TOKEN is not configured; refusing all {} traffic", GUARDED_PREFIX);
            unauthorized(response, "service token not configured");
            return;
        }
        if (presented == null || presented.isBlank() || !constantTimeEquals(presented, expected)) {
            log.warn("Rejected {} request to {}: invalid or missing {}",
                    request.getMethod(), request.getRequestURI(), HEADER);
            unauthorized(response, "invalid or missing " + HEADER);
            return;
        }
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        ErrorResponse body = new ErrorResponse(
                "UNAUTHORIZED",
                message,
                CorrelationIds.current(),
                Instant.now(),
                Map.of("header", HEADER));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(Json.write(body));
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
