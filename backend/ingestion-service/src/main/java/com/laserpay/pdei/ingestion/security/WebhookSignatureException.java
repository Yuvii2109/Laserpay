package com.laserpay.pdei.ingestion.security;

/**
 * A webhook could not be authenticated: no registered secret for the source, a missing or malformed
 * signature header, a signature that does not match, or a timestamp outside the replay window.
 *
 * <p>Deliberately <em>not</em> a {@code PdeiException} subclass - that hierarchy is sealed and
 * belongs to {@code platform-common}. It is also deliberately vague in its message: a signature
 * failure must never tell an attacker which half of the check failed. The detail goes to the log,
 * which is where the operator is.
 *
 * <p>Mapped to {@code 401 Unauthorized} by
 * {@code com.laserpay.pdei.ingestion.controller.IngestionExceptionHandler}.
 */
public class WebhookSignatureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable code echoed in the {@code ErrorResponse}. */
    public static final String CODE = "WEBHOOK_SIGNATURE_INVALID";

    private final String sourceSystem;

    public WebhookSignatureException(String sourceSystem, String message) {
        super(message);
        this.sourceSystem = sourceSystem;
    }

    public String sourceSystem() {
        return sourceSystem;
    }
}
