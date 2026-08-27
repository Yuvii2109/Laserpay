package com.laserpay.pdei.ingestion.controller;

import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.error.PdeiException;
import com.laserpay.pdei.common.error.ValidationException;
import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.ingestion.security.WebhookSignatureException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates failures into the platform's single error shape,
 * {@code com.laserpay.pdei.common.error.ErrorResponse}, so that a caller sees the same
 * {@code {code, message, correlationId, at, details}} from ingestion as from api-gateway-service.
 *
 * <p>Status mapping:
 * <ul>
 *   <li>{@link PdeiException} - its own {@code httpStatus()}, so {@link ValidationException} is 400
 *       and an unreachable Kafka is 503, without a translation table here;</li>
 *   <li>{@link WebhookSignatureException} - 401, with a deliberately uninformative message;</li>
 *   <li>unreadable body / wrong content type - 400, because the request is wrong, not the events;</li>
 *   <li>anything else - 500 with no detail leaked, the cause logged at ERROR.</li>
 * </ul>
 *
 * <p>Per-event validation failures never reach here: they are reported inside the {@code 202} body
 * as {@code rejected[]} entries. This handler is for failures of the <em>request</em>.
 *
 * <p>Every response carries a correlation id, taken from the MDC when tracing populated it and
 * generated otherwise, so a caller with a failed request and an operator with a log line have one
 * shared handle.
 */
@RestControllerAdvice
public class IngestionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionExceptionHandler.class);

    private final Clocks clock;

    public IngestionExceptionHandler(Clocks clock) {
        this.clock = clock;
    }

    @ExceptionHandler(PdeiException.class)
    public ResponseEntity<ErrorResponse> handlePdei(PdeiException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(e.httpStatus()) == null
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.valueOf(e.httpStatus());
        String correlationId = correlationId();
        if (status.is5xxServerError()) {
            log.error("{} {} failed [{}]: {}", request.getMethod(), request.getRequestURI(), correlationId,
                    e.getMessage(), e);
        } else {
            log.warn("{} {} rejected [{}]: {}", request.getMethod(), request.getRequestURI(), correlationId,
                    e.getMessage());
        }
        return ResponseEntity.status(status).body(e.toErrorResponse(correlationId, clock.now()));
    }

    /**
     * A failed webhook signature. The body says only that verification failed - which of the
     * secret, the digest, the timestamp or the source registration was wrong is an oracle an
     * attacker would enjoy, and it is already in the log where the operator is.
     */
    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<ErrorResponse> handleSignature(WebhookSignatureException e,
                                                         HttpServletRequest request) {
        String correlationId = correlationId();
        log.warn("Unauthenticated webhook on {} from source '{}' [{}]",
                request.getRequestURI(), e.sourceSystem(), correlationId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(WebhookSignatureException.CODE,
                        "Webhook signature verification failed", correlationId, clock.now(),
                        Map.of("sourceSystem", String.valueOf(e.sourceSystem()))));
    }

    /** Unparseable JSON, a body that is not an array where one is required, and similar. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception e, HttpServletRequest request) {
        String correlationId = correlationId();
        log.warn("Malformed request {} {} [{}]: {}", request.getMethod(), request.getRequestURI(),
                correlationId, e.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(ValidationException.CODE,
                "Request body could not be read: " + rootMessage(e), correlationId, clock.now(),
                Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        String correlationId = correlationId();
        log.error("Unhandled failure on {} {} [{}]", request.getMethod(), request.getRequestURI(),
                correlationId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.internal(correlationId, clock.now()));
    }

    private static String correlationId() {
        String fromMdc = MDC.get("correlationId");
        if (fromMdc == null || fromMdc.isBlank()) {
            fromMdc = MDC.get("traceId");
        }
        return fromMdc == null || fromMdc.isBlank() ? UUID.randomUUID().toString() : fromMdc;
    }

    /** The innermost message, which is the one that says what actually went wrong. */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        // Jackson messages carry the whole failing document in some cases; keep the response bounded.
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
