package com.laserpay.pdei.common.error;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root of the PDEI failure hierarchy (docs/SHARED-LIBRARY-API.md section 1.8).
 *
 * <p>Sealed: the permitted set is part of the frozen shared API, so a service module can exhaustively
 * switch over failures and the Temporal activity retry policy can name non-retryable classes
 * ({@link PolicyViolationException}, {@link ValidationException}) with confidence that no new
 * subtype appears behind its back.
 *
 * <p>Each subclass carries a stable machine-readable {@link #code()} (surfaced in
 * {@link ErrorResponse}) and an advisory {@link #httpStatus()} that api-gateway-service maps
 * straight onto its responses without needing its own translation table.
 */
public sealed class PdeiException extends RuntimeException
        permits ValidationException, PolicyViolationException, EvidenceIntegrityException,
                NotFoundException, ConflictException, UpstreamUnavailableException,
                UnknownEventTypeException {

    private final String code;
    private final int httpStatus;
    private final transient Map<String, Object> details;

    protected PdeiException(String code, int httpStatus, String message,
                            Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** Stable, machine-readable error code, e.g. {@code VALIDATION_ERROR}. */
    public String code() {
        return code;
    }

    /** Advisory HTTP status for api-gateway-service. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Additional structured context; never null, possibly empty, always immutable. */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * Whether retrying the same operation could plausibly succeed. Only transient upstream
     * failures are retryable; everything else is a contract or data problem.
     */
    public boolean isRetryable() {
        return this instanceof UpstreamUnavailableException;
    }

    public ErrorResponse toErrorResponse(String correlationId, Instant at) {
        return new ErrorResponse(code, getMessage(), correlationId, at, details);
    }

    public ErrorResponse toErrorResponse(String correlationId) {
        return toErrorResponse(correlationId, Instant.now());
    }
}
