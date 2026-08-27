package com.laserpay.pdei.ingestion.model;

/**
 * One field-level validation failure, structured so a caller can fix the emitting adapter without
 * reading prose.
 *
 * @param field      dotted path into the submission, e.g. {@code body.amount.currency}
 * @param message    human-readable failure, as produced by the JSON Schema validator
 * @param code       validation keyword that failed, e.g. {@code required}, {@code type},
 *                   {@code pattern}, or a PDEI code such as {@code UNKNOWN_SCHEMA}
 * @param schemaPath location of the failing keyword inside the schema, for schema debugging
 */
public record FieldError(String field, String message, String code, String schemaPath) {

    public static FieldError of(String field, String message, String code) {
        return new FieldError(field, message, code, null);
    }
}
