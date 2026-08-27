package com.laserpay.pdei.api.config;

import com.laserpay.pdei.api.support.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Correlation-Id} from the request (or mints one), puts it in the MDC for the life of
 * the request, and echoes it on the response.
 *
 * <p>This is the first filter in the chain so that everything downstream, including the rate limiter
 * rejecting a request and {@code GlobalExceptionHandler} rendering an error, can name the same id.
 * The id is also copied to a request attribute so a controller can read it without touching the MDC.</p>
 *
 * <p>The MDC is always cleared in a {@code finally} block: servlet containers pool threads, and a
 * leaked correlation id would silently mislabel the next unrelated request.</p>
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Request attribute carrying the resolved correlation id. */
    public static final String ATTRIBUTE = "pdei.correlationId";

    /** Query parameter the merchant scope is read from, for MDC enrichment only. */
    private static final String MERCHANT_PARAM = "merchantId";

    private static final String MERCHANT_HEADER = "X-PDEI-Merchant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = firstNonBlank(
                request.getHeader(CorrelationIds.HEADER),
                request.getHeader("X-Request-Id"));
        if (correlationId == null) {
            correlationId = CorrelationIds.generate();
        }
        try {
            CorrelationIds.bind(correlationId);
            CorrelationIds.bindMerchant(firstNonBlank(
                    request.getParameter(MERCHANT_PARAM), request.getHeader(MERCHANT_HEADER)));
            request.setAttribute(ATTRIBUTE, correlationId);
            response.setHeader(CorrelationIds.HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            CorrelationIds.clear();
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
