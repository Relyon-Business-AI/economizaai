package com.relyon.economizaai.exception;

/**
 * Thrown when a captcha solver IS configured but couldn't return a token
 * (provider error, timeout, out of balance). Upstream failure → surfaced as
 * 502, like {@link SefazFetchException}.
 */
public class CaptchaSolveFailedException extends DomainException {

    public CaptchaSolveFailedException(String reason) {
        super("receipt.captcha.failed", reason);
    }
}
