package com.laserpay.pdei.common.error;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire shape for every error response across the platform
 * (docs/SHARED-LIBRARY-API.md section 1.8).
 *
 * <p>{@code correlationId} is mandatory in spirit: it is what lets an operator jump from a failed
 * API call to the Loki log line, the trace in Tempo, and the audit record.
 */
public record ErrorResponse(String code,
                            String message,
                            String correlationId,
                            Instant at,
                            Map<String, Object> details) {

    public ErrorResponse {
        details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static ErrorResponse of(PdeiException e, String correlationId, Instant at) {
        return new ErrorResponse(e.code(), e.getMessage(), correlationId, at, e.details());
    }

    /** Fallback for non-PDEI failures; never leaks the exception class or stack trace. */
    public static ErrorResponse internal(String correlationId, Instant at) {
        return new ErrorResponse("INTERNAL_ERROR", "Internal error", correlationId, at, Map.of());
    }
}
