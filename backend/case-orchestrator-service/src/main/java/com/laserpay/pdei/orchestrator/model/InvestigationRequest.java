package com.laserpay.pdei.orchestrator.model;

/**
 * Argument of activity 6, {@code investigate}.
 *
 * @param useAi            false means "run {@code DeterministicInvestigator} only". Contract
 *                         section 10 calls step 6 skippable; this flag is that skip, expressed so
 *                         that the workflow still always ends up with an
 *                         {@code InvestigationResult} for the gate to judge.
 * @param admissionReason  why admission control decided what it decided, carried through so the
 *                         stored investigation row explains itself
 * @param idempotencyToken stable per assessment round. Retries of the same activity reuse the
 *                         memoised result instead of spending another model call.
 */
public record InvestigationRequest(
        CaseRef ref,
        boolean useAi,
        String admissionReason,
        String idempotencyToken) {
}
