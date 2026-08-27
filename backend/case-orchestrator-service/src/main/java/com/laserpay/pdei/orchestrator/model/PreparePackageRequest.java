package com.laserpay.pdei.orchestrator.model;

/**
 * Argument of activity 9, {@code prepareRepresentmentPackage}.
 *
 * @param idempotencyToken stable for one assessment round. {@code CaseAssemblyService.assemble}
 *                        writes a NEW package version on every call, so without memoisation an
 *                        activity retry would inflate the version number and leave orphan bundles
 *                        in {@code pdei-packages}.
 */
public record PreparePackageRequest(
        CaseRef ref,
        String approvedBy,
        String idempotencyToken) {
}
