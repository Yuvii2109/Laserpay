package com.laserpay.pdei.api.support;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * The correlation id of the request currently being served.
 *
 * <p>{@code CorrelationIdFilter} puts the value in the SLF4J {@link MDC} for the duration of the
 * request; every log line and every {@code ErrorResponse} reads it back from here. Using the MDC as
 * the carrier (rather than a private ThreadLocal) means the value also reaches the log encoder and
 * anything else that already understands MDC, with no extra plumbing.</p>
 */
public final class CorrelationIds {

    /** Inbound and outbound HTTP header name (contract section 13: every log line carries it). */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key, matching the logging pattern in application.yml. */
    public static final String MDC_KEY = "correlationId";

    /** MDC key for the merchant scope of the request, when the route carries one. */
    public static final String MDC_MERCHANT_KEY = "merchantId";

    private CorrelationIds() {
    }

    /** The current correlation id, or a freshly generated one when the filter did not run. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? generate() : value;
    }

    /** The current correlation id, or {@code null} when none is bound. */
    public static String currentOrNull() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static void bind(String correlationId) {
        MDC.put(MDC_KEY, correlationId == null || correlationId.isBlank() ? generate() : correlationId);
    }

    public static void bindMerchant(String merchantId) {
        if (merchantId != null && !merchantId.isBlank()) {
            MDC.put(MDC_MERCHANT_KEY, merchantId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
        MDC.remove(MDC_MERCHANT_KEY);
    }
}
