package com.relyon.economizaai.exception;

/**
 * A user hit their per-day cap for a paid external service (Infosimples query or
 * captcha solve). Thrown before the money is spent — the cap is an abuse/cost
 * guard independent of subscription tier.
 */
public class PaidApiQuotaExceededException extends DomainException {

    public PaidApiQuotaExceededException(String service) {
        super("receipt.paid_api.quota_exceeded", service);
    }

    /** Same 429 semantics with a context-specific message (e.g. the phone-OTP flow). */
    public PaidApiQuotaExceededException(String messageKey, String service) {
        super(messageKey, service);
    }
}
