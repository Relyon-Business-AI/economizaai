package com.relyon.economizaai.exception;

/**
 * The daily spend ceiling of a paid external service (or the optional overall
 * ceiling) has been reached. That service fails fast until the budget resets
 * (midnight UTC), so a viral spike can't run up an unbounded bill.
 */
public class PaidApiBudgetExceededException extends DomainException {

    public PaidApiBudgetExceededException() {
        super("receipt.paid_api.budget_exhausted");
    }
}
