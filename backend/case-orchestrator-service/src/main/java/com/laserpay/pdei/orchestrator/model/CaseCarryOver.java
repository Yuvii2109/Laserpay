package com.laserpay.pdei.orchestrator.model;

/**
 * The slice of workflow state that survives a continue-as-new.
 *
 * <p>Continue-as-new resets the event history, so anything the next run needs must travel in the
 * input. Only the two genuinely long waits continue as new - step 4 (up to 7 days) and step 11 (up
 * to 45 days) - so this record carries exactly what those two need to resume without redoing work
 * or restarting their budgets.</p>
 *
 * @param evidenceWaitElapsedMillis how much of the 7-day step 4 budget previous runs already spent
 * @param followUpElapsedMillis     how much of the step 11 follow-up window previous runs spent
 * @param followUpTick              number of follow-up ticks already emitted, so tick-derived
 *                                  idempotency keys never repeat across runs
 * @param assessmentRound           how many times steps 2-8 have already executed
 * @param packageResult             the assembled package, when step 9 already ran
 * @param submissionReceipt         the network receipt, when step 10 already ran
 */
public record CaseCarryOver(
        long evidenceWaitElapsedMillis,
        long followUpElapsedMillis,
        int followUpTick,
        int assessmentRound,
        PackageResult packageResult,
        SubmissionReceipt submissionReceipt) {

    public static CaseCarryOver empty() {
        return new CaseCarryOver(0L, 0L, 0, 0, null, null);
    }

    public static CaseCarryOver orEmpty(CaseCarryOver value) {
        return value == null ? empty() : value;
    }
}
