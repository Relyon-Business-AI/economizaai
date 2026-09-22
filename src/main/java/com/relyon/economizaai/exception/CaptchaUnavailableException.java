package com.relyon.economizaai.exception;

/**
 * Thrown when a receipt belongs to a captcha-gated SEFAZ portal (e.g. MS) but
 * no captcha solver is configured, so we can't reach the DANFE. Distinct from
 * {@link UnsupportedStateException} — the state IS supported, the solver just
 * isn't switched on yet. Surfaced as 503 so the FE can say "coming soon".
 */
public class CaptchaUnavailableException extends DomainException {

    public CaptchaUnavailableException(String uf) {
        super("receipt.captcha.unavailable", uf);
    }
}
