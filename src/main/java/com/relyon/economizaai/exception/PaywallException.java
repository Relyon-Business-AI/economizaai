package com.relyon.economizaai.exception;

import lombok.Getter;

/**
 * Raised when a FREE-tier user attempts an action reserved for PRO. Mapped to
 * HTTP 402 Payment Required by {@link GlobalExceptionHandler}. The gated feature
 * name is a machine enum name, so it is kept for logs only and never interpolated
 * into the user-facing message.
 */
@Getter
public class PaywallException extends DomainException {

    private final String featureName;

    public PaywallException(String featureName) {
        super("subscription.upgrade_required");
        this.featureName = featureName;
    }
}
