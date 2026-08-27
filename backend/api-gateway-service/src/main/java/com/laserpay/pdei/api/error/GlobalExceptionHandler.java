package com.laserpay.pdei.api.error;

import com.laserpay.pdei.api.support.CorrelationIds;
import com.laserpay.pdei.common.error.ConflictException;
import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.error.EvidenceIntegrityException;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.PdeiException;
import com.laserpay.pdei.common.error.PolicyViolationException;
import com.laserpay.pdei.common.error.UnknownEventTypeException;
import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.time.Clocks;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to the shared {@link ErrorResponse} shape, always carrying the correlation id.
 *
 * <p>The correlation id is the whole point of this class. A merchant, an operator and a developer
 * reading Loki must be able to join the response the browser showed to the exact request in the
 * logs, so no error, including an unexpected one, is allowed to leave without it.</p>
 *
 * <h2>Status mapping</h2>
 * <table>
 *   <tr><th>Exception</th><th>Status</th><th>Why</th></tr>
 *   <tr><td>{@link NotFoundException}</td><td>404</td><td>the entity does not exist</td></tr>
 *   <tr><td>{@link ValidationException}</td><td>400</td><td>the request itself is malformed</td></tr>
 *   <tr><td>{@link PolicyViolationException}</td><td>409</td>
 *       <td>the request is well formed but conflicts with the policy in force</td></tr>
 *   <tr><td>{@link ConflictException}</td><td>409</td><td>illegal state transition</td></tr>
 *   <tr><td>{@link EvidenceIntegrityException}</td><td>422</td>
 *       <td>the request is understood but the stored evidence cannot be trusted</td></tr>
 *   <tr><td>{@link UpstreamUnavailableException}</td><td>503</td><td>a dependency is down; retryable</td></tr>
 *   <tr><td>{@link UnknownEventTypeException}</td><td>400</td><td>unrecognised wire value</td></tr>
 * </table>
 *
 * <p><strong>Deliberate divergence.</strong> {@code PdeiException.httpStatus()} in platform-common
 * carries 422 for {@code PolicyViolationException} and 409 for {@code EvidenceIntegrityException},
 * which is the opposite of the table above. The gateway's HTTP surface is specified by the table, so
 * the mapping here is explicit per exception type and does not read {@code httpStatus()}. That
 * divergence is recorded in this module's {@code context.md} known gaps: if the platform ever
 * reconciles the two, this is the one place to change.</p>
 *
 * <p>Only unexpected failures are logged at ERROR with a stack trace. A 404 for a mistyped id is a
 * normal outcome, not an incident, and logging it loudly would bury the real ones.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clocks clock = Clocks.system();

    // ---------------------------------------------------------------------------------------
    // The shared PdeiException hierarchy
    // ---------------------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return respond(e, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException e) {
        return respond(e, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(PolicyViolationException.class)
    public ResponseEntity<ErrorResponse> handlePolicyViolation(PolicyViolationException e) {
        return respond(e, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException e) {
        return respond(e, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(EvidenceIntegrityException.class)
    public ResponseEntity<ErrorResponse> handleEvidenceIntegrity(EvidenceIntegrityException e) {
        return respond(e, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamUnavailable(UpstreamUnavailableException e) {
        log.warn("Upstream unavailable [{}]: {}", correlationId(), e.getMessage());
        return respond(e, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(UnknownEventTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnknownEventType(UnknownEventTypeException e) {
        return respond(e, HttpStatus.BAD_REQUEST);
    }

    /**
     * Catch-all for the sealed hierarchy. A new permitted subtype would otherwise fall through to
     * the 500 handler and be reported as a platform bug rather than as the client error it is.
     */
    @ExceptionHandler(PdeiException.class)
    public ResponseEntity<ErrorResponse> handlePdei(PdeiException e) {
        HttpStatus status = HttpStatus.resolve(e.httpStatus());
        return respond(e, status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status);
    }

    // ---------------------------------------------------------------------------------------
    // Spring MVC and bean validation failures, translated into the same shape
    // ---------------------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            details.put(error.getField(), error.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors()
                .forEach(error -> details.put(error.getObjectName(), error.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, ValidationException.CODE, "Request validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            details.put(String.valueOf(violation.getPropertyPath()), violation.getMessage());
        }
        return body(HttpStatus.BAD_REQUEST, ValidationException.CODE, "Request validation failed", details);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, ValidationException.CODE,
                "Missing required parameter: " + e.getParameterName(),
                Map.of("parameter", e.getParameterName(), "type", e.getParameterType()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return body(HttpStatus.BAD_REQUEST, ValidationException.CODE,
                "Missing required multipart part: " + e.getRequestPartName(),
                Map.of("part", e.getRequestPartName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Class<?> required = e.getRequiredType();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", e.getName());
        details.put("value", String.valueOf(e.getValue()));
        if (required != null) {
            details.put("expectedType", required.getSimpleName());
            if (required.isEnum()) {
                // Listing the accepted values turns "that is not a ReadinessBand" into an answer the
                // caller can act on without opening the contract document.
                details.put("allowedValues", Arrays.stream(required.getEnumConstants())
                        .map(String::valueOf).toList());
            }
        }
        return body(HttpStatus.BAD_REQUEST, ValidationException.CODE,
                "Invalid value for parameter: " + e.getName(), details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, ValidationException.CODE,
                "Malformed request body", Map.of("detail", rootMessage(e)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        List<String> supported = e.getSupportedHttpMethods() == null
                ? List.of()
                : e.getSupportedHttpMethods().stream().map(String::valueOf).toList();
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method not supported: " + e.getMethod(),
                Map.of("method", String.valueOf(e.getMethod()), "supported", supported));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandler(Exception e) {
        return body(HttpStatus.NOT_FOUND, NotFoundException.CODE, "No such route", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Uploaded file exceeds the configured maximum",
                Map.of("maxBytes", e.getMaxUploadSize()));
    }

    // ---------------------------------------------------------------------------------------
    // Everything else
    // ---------------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String correlationId = correlationId();
        log.error("Unhandled exception [{}]", correlationId, e);
        // The message is deliberately generic: an internal failure must not leak SQL, object keys or
        // stack frames to a merchant. The correlation id is how an operator finds the real cause.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.internal(correlationId, clock.now()));
    }

    // ---------------------------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> respond(PdeiException e, HttpStatus status) {
        String correlationId = correlationId();
        if (status.is5xxServerError()) {
            log.error("{} [{}]: {}", e.code(), correlationId, e.getMessage(), e);
        } else {
            log.debug("{} [{}]: {}", e.code(), correlationId, e.getMessage());
        }
        return ResponseEntity.status(status).body(e.toErrorResponse(correlationId, clock.now()));
    }

    private ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message,
                                               Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, correlationId(), clock.now(), details));
    }

    private String correlationId() {
        return CorrelationIds.current();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    /** Exposed for tests that build a standalone MockMvc. */
    Instant now() {
        return clock.now();
    }
}
