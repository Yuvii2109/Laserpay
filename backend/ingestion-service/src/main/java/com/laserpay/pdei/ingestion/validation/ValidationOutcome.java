package com.laserpay.pdei.ingestion.validation;

import com.laserpay.pdei.ingestion.model.FieldError;
import java.util.List;

/**
 * Result of validating one submitted raw event.
 *
 * <p>Structured rather than boolean because the whole point of validating at the front door is to
 * tell the adapter author precisely which field is wrong. A rejection with "invalid payload" costs
 * someone an afternoon; a rejection with {@code body.amount.currency: does not match ^[A-Z]{3}$}
 * costs them a minute.
 *
 * @param valid      true when the event may proceed to dedupe and publication
 * @param errors     field-level failures; empty when valid
 * @param schemaName the schema that was applied, or null when none was registered for the event
 *                   type (which is only an error if {@code ingestion.schemas.fail-on-unknown-event-type})
 * @param code       rejection code to report when invalid, see
 *                   {@link com.laserpay.pdei.ingestion.model.RejectedEvent}
 */
public record ValidationOutcome(boolean valid, List<FieldError> errors, String schemaName, String code) {

    public ValidationOutcome {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ValidationOutcome valid(String schemaName) {
        return new ValidationOutcome(true, List.of(), schemaName, null);
    }

    public static ValidationOutcome invalid(String schemaName, String code, List<FieldError> errors) {
        return new ValidationOutcome(false, errors, schemaName, code);
    }

    /** One-line summary used as the {@code message} of the rejection entry. */
    public String summary() {
        if (valid) {
            return "valid";
        }
        if (errors.isEmpty()) {
            return "rejected: " + code;
        }
        FieldError first = errors.get(0);
        String head = first.field() + ": " + first.message();
        return errors.size() == 1 ? head : head + " (and " + (errors.size() - 1) + " more)";
    }
}
