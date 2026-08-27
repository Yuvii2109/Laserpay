package com.laserpay.pdei.core.ai;

/**
 * Rate limit and daily spend cap for AI calls.
 *
 * <p>Separated from {@link AdmissionController} so the priority formula can be unit tested without
 * Redis, and so a deployment can swap in a different throttling strategy.</p>
 */
public interface AiBudgetGate {

    /** Try to consume one token from the per-second bucket ({@code pdei:ai:bucket}). */
    boolean tryConsumeToken();

    /** Try to consume one call from today's budget ({@code pdei:ai:budget:{yyyy-MM-dd}}). */
    boolean tryConsumeDailyBudget();

    /** Give a token and a budget slot back when the call never happened. */
    void refund();

    /** Calls used today, for the observability screen. */
    long usedToday();

    /** Configured daily budget. */
    long dailyBudget();

    /** A gate that never throttles - used in tests and when Redis is absent by design. */
    static AiBudgetGate unlimited() {
        return new AiBudgetGate() {
            @Override
            public boolean tryConsumeToken() {
                return true;
            }

            @Override
            public boolean tryConsumeDailyBudget() {
                return true;
            }

            @Override
            public void refund() {
                // nothing to give back
            }

            @Override
            public long usedToday() {
                return 0L;
            }

            @Override
            public long dailyBudget() {
                return Long.MAX_VALUE;
            }
        };
    }
}
