package com.laserpay.pdei.docproc.web;

import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.docproc.extract.ExtractionFailedException;
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
 * Maps failures onto the shared {@code ErrorResponse} shape (docs/SHARED-LIBRARY-API.md 1.8), so
 * a client sees the same envelope from every PDEI service.
 *
 * <p>{@code correlationId} comes from the MDC, where the tracing filter puts it; a response
 * without one is still valid, it just cannot be joined to a Loki query.
 */
@RestControllerAdvice
public class DocProcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DocProcExceptionHandler.class);

    private final Clocks clock;

    public DocProcExceptionHandler(Clocks clock) {
        this.clock = clock;
    }

    /**
     * An undecodable artifact is a 422, not a 500: the request was well-formed and the service is
     * healthy - the document is the problem, and retrying the same bytes will not help.
     */
    @ExceptionHandler(ExtractionFailedException.class)
    public ResponseEntity<ErrorResponse> onExtractionFailed(ExtractionFailedException e) {
        log.warn("extraction failed in {}: {}", e.extractor(), e.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACTION_FAILED", e.getMessage(),
                Map.of("extractor", String.valueOf(e.extractor())));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), Map.of());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> onValidation(ValidationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onBeanValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("request body is invalid");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), Map.of());
    }

    /** Unparseable request body. Without this it would fall through to the 500 handler below. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "request body could not be parsed", Map.of());
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
                e.getReason() == null ? e.getMessage() : e.getReason(), Map.of());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> onUnexpected(RuntimeException e) {
        log.error("unhandled error in document-processor-service", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "unexpected error: " + e.getClass().getSimpleName(), Map.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                Map<String, Object> details) {
        ErrorResponse body = new ErrorResponse(code,
                message == null ? status.getReasonPhrase() : message,
                MDC.get("correlationId"), clock.now(), details);
        return ResponseEntity.status(status).body(body);
    }
}
