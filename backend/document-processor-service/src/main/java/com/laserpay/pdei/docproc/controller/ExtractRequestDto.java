package com.laserpay.pdei.docproc.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /docproc/v1/extract}.
 *
 * <p>The contract names one field, {@code objectKey}. {@code bucket} is optional and defaults to
 * {@code pdei-evidence}; it exists so a representment package under {@code pdei-packages} can be
 * inspected with the same endpoint rather than a second one.
 *
 * @param objectKey MinIO key, e.g. {@code MER-1/TX-9/DELIVERY_PROOF/EV-3/v1/pod.pdf}
 * @param bucket    optional bucket override
 */
public record ExtractRequestDto(@NotBlank @Size(max = 1024) String objectKey,
                                @Size(max = 63) String bucket) {
}
