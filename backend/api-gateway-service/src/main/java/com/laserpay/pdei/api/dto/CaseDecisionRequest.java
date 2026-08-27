package com.laserpay.pdei.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /cases/{caseId}/approve}, {@code /reject} and {@code /submit}.
 *
 * <p>{@code actor} is mandatory. A human decision that cannot name the human who made it is not an
 * auditable decision, and this endpoint writes to the hash-chained audit log.</p>
 *
 * @param note free text shown in the case history; required in practice for a rejection, which is
 *             enforced by the reject route rather than by the shape, because approve and submit
 *             legitimately have nothing to say
 */
public record CaseDecisionRequest(
        @NotBlank(message = "actor is required: a human decision must name the human")
        @Size(max = 128, message = "actor must be at most 128 characters")
        String actor,

        @Size(max = 2000, message = "note must be at most 2000 characters")
        String note) {

    public String noteOrDefault(String fallback) {
        return note == null || note.isBlank() ? fallback : note;
    }
}
