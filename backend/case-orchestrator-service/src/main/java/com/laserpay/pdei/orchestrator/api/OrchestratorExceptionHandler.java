package com.laserpay.pdei.orchestrator.api;

import com.laserpay.pdei.common.error.ErrorResponse;
import com.laserpay.pdei.common.error.PdeiException;
import com.laserpay.pdei.common.time.Clocks;
import io.temporal.client.WorkflowNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Turns failures into the platform's {@code ErrorResponse} shape so the api-gateway can relay them
 * without a translation table of its own.
 *
 * <p>{@link PdeiException} already carries a stable {@code code} and an advisory HTTP status, so
 * this advice only has to pass them through. Temporal's own "no such workflow" becomes a 404,
 * because from the caller's point of view that is exactly what it is.</p>
 */
@RestControllerAdvice
public class OrchestratorExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorExceptionHandler.class);

    private final Clocks clock;

    public OrchestratorExceptionHandler(Clocks clock) {
        this.clock = clock;
    }

    @ExceptionHandler(PdeiException.class)
    public ResponseEntity<ErrorResponse> onPdeiException(PdeiException e) {
        log.warn("{} -> {}: {}", e.code(), e.httpStatus(), e.getMessage());
        return ResponseEntity.status(e.httpStatus())
                .body(new ErrorResponse(e.code(), e.getMessage(), null, clock.now(), e.details()));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ErrorResponse> onWorkflowNotFound(WorkflowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CASE_WORKFLOW_NOT_FOUND",
                        "no Temporal execution for this case", null, clock.now(),
                        Map.<String, Object>of("detail", String.valueOf(e.getMessage()))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", String.valueOf(e.getMessage()), null,
                        clock.now(), Map.of()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> onUnexpected(RuntimeException e) {
        log.error("unhandled failure in the orchestrator ops API", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "unexpected orchestrator failure", null,
                        clock.now(), Map.<String, Object>of("type", e.getClass().getSimpleName())));
    }
}
