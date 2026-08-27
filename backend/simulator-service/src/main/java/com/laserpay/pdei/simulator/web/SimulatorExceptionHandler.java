package com.laserpay.pdei.simulator.web;

import com.laserpay.pdei.common.error.ConflictException;
import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.time.Clocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Maps failures onto the shared {@code ErrorResponse} shape (docs/SHARED-LIBRARY-API.md 1.8).
 *
 * <p>The status codes carry meaning worth getting right for this service in particular:
 * a chaos injection that cannot be applied is a {@code 400} (the request asked for something
 * impossible in the current state - "duplicate the next 20 events" with no run in flight), while
 * "already at max concurrent runs" is a {@code 409}, because the same request will succeed once a
 * run finishes.
 */
@RestControllerAdvice
public class SimulatorExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulatorExceptionHandler.class);

    private final Clocks clock;

    public SimulatorExceptionHandler(Clocks clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> onValidation(ValidationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> onConflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> onUpstream(UpstreamUnavailableException e) {
        log.warn("upstream unavailable: {}", e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onBeanValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("request body is invalid");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    /** Unparseable request body. Without this it would fall through to the 500 handler below. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "request body could not be parsed");
    }

    /**
     * Anything the framework already decided a status for - notably
     * {@code HandlerMethodValidationException} from the {@code @Validated} constraints on path
     * variables and request parameters, which is a 400 and must not be flattened into a 500 by
     * the catch-all below.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> onResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        return build(status == null ? HttpStatus.BAD_REQUEST : status, "REQUEST_REJECTED",
                e.getReason() == null ? e.getMessage() : e.getReason());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> onUnexpected(RuntimeException e) {
        log.error("unhandled error in simulator-service", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "unexpected error: " + e.getClass().getSimpleName());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        ErrorResponse body = new ErrorResponse(code,
                message == null ? status.getReasonPhrase() : message,
                MDC.get("correlationId"), clock.now(), Map.of());
        return ResponseEntity.status(status).body(body);
    }
}
