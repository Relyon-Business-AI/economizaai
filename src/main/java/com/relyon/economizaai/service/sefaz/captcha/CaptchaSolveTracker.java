package com.relyon.economizaai.service.sefaz.captcha;

import com.relyon.economizaai.exception.CaptchaSolveFailedException;

/**
 * Thread-local count of PAID captcha solves performed during the current fetch.
 * Solver implementations call {@link #recordSolve()} at the exact moment a token
 * is obtained (the billable event); {@link com.relyon.economizaai.service.sefaz.SefazIngestionService}
 * drains the counter after the fetch to write one ledger entry PER real solve —
 * captcha-free scrapes (GO, SVRS, SP) therefore record nothing, while a retry
 * loop that re-solved three tokens records three.
 */
public final class CaptchaSolveTracker {

    private static final ThreadLocal<Integer> SOLVE_COUNT = ThreadLocal.withInitial(() -> 0);

    private static final ThreadLocal<Boolean> SPEND_BLOCKED = ThreadLocal.withInitial(() -> false);

    private CaptchaSolveTracker() {
    }

    /**
     * Set by the ingestion at the start of every fetch: when the daily paid budget is gone,
     * solvers must refuse to buy a token — while captcha-free portals keep scraping for free.
     */
    public static void blockSpend(boolean blocked) {
        SPEND_BLOCKED.set(blocked);
    }

    /** Solver implementations call this before buying a token. */
    public static void assertSpendAllowed() {
        if (SPEND_BLOCKED.get()) {
            throw new CaptchaSolveFailedException(BUDGET_EXHAUSTED_REASON);
        }
    }

    public static final String BUDGET_EXHAUSTED_REASON = "daily-budget-exhausted";

    /** Called by a {@link CaptchaSolver} implementation when a paid token was obtained. */
    public static void recordSolve() {
        SOLVE_COUNT.set(SOLVE_COUNT.get() + 1);
    }

    /** Returns the solves accumulated on this thread and resets the counter. */
    public static int drain() {
        var solves = SOLVE_COUNT.get();
        SOLVE_COUNT.remove();
        return solves;
    }
}
