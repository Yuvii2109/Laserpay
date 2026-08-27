package com.laserpay.pdei.core.util;

import com.laserpay.pdei.common.error.ConflictException;
import com.laserpay.pdei.common.error.EvidenceIntegrityException;
import com.laserpay.pdei.common.error.NotFoundException;
import com.laserpay.pdei.common.error.PolicyViolationException;
import com.laserpay.pdei.common.error.UpstreamUnavailableException;
import com.laserpay.pdei.common.error.ValidationException;

/**
 * Single construction point for the sealed {@code PdeiException} hierarchy owned by
 * {@code platform-common}.
 *
 * <p>Every throw site in evidence-core goes through here so that, if the shared exception
 * constructors change shape, exactly one file needs to be adapted.</p>
 */
public final class CoreErrors {

    private CoreErrors() {
    }

    public static NotFoundException notFound(String entityType, String id) {
        return new NotFoundException(entityType + " not found: " + id);
    }

    public static ValidationException invalid(String message) {
        return new ValidationException(message);
    }

    public static ValidationException required(String field) {
        return new ValidationException("required field is missing or blank: " + field);
    }

    public static ConflictException conflict(String message) {
        return new ConflictException(message);
    }

    public static PolicyViolationException policyViolation(String message) {
        return new PolicyViolationException(message);
    }

    public static EvidenceIntegrityException integrity(String message) {
        return new EvidenceIntegrityException(message);
    }

    /**
     * @param upstream the dependency that failed (MinIO, Postgres, Redis, Kafka, Temporal,
     *     ai-reasoning-service). It is carried in the exception's details map so structured logs
     *     and metrics can attribute the failure without parsing the message.
     */
    public static UpstreamUnavailableException upstream(String upstream, String message) {
        return new UpstreamUnavailableException(upstream, message);
    }

    /** Guard for a required string argument. */
    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw required(field);
        }
        return value;
    }

    /** Guard for a required object argument. */
    public static <T> T requireValue(T value, String field) {
        if (value == null) {
            throw required(field);
        }
        return value;
    }
}
