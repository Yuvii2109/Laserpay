package com.laserpay.pdei.core.ai;

/**
 * Response of {@code POST /v1/admission/score} on the AI service.
 *
 * <p>Advisory only. The Java {@link AdmissionController} owns the real decision, because admission
 * control is a cost and safety control and must not depend on the service it is throttling.</p>
 */
public record AdmissionScore(boolean admit, int priority, String reason) {
}
