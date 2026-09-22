package com.relyon.economizaai.exception;

/**
 * Raised when a FREE-tier user attempts an action reserved for PRO. Mapped to
 * HTTP 402 Payment Required by {@link GlobalExceptionHandler}. The single
 * argument is the gated feature name so the FE can tailor the upgrade prompt.
 */
public class PaywallException extends DomainException {

    public PaywallException(String featureName) {
        super("subscription.upgrade_required", featureName);
    }
}
