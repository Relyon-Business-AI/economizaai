package com.relyon.economizaai.exception;

/**
 * The global daily spend ceiling for paid external calls has been reached — the
 * cost kill-switch. Every paid call fails fast until the budget resets (midnight
 * UTC), so a viral spike can't run up an unbounded bill.
 */
public class PaidApiBudgetExceededException extends DomainException {

    public PaidApiBudgetExceededException() {
        super("receipt.paid_api.budget_exhausted");
    }
}
