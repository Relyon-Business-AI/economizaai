package com.relyon.economizaai.service.sefaz.captcha;

import com.relyon.economizaai.exception.CaptchaUnavailableException;

/**
 * Provider-agnostic captcha-solving seam. Implementations call a solver service
 * (2Captcha, Anti-Captcha, CapMonster, …) which returns the token a page's
 * captcha widget expects. Exactly one implementation is active, chosen by
 * {@code economizaai.captcha.provider} (default {@code none} → {@link NoopCaptchaSolver}).
 *
 * <p>To onboard a new provider whose HTTP API differs from 2Captcha's: add a
 * class implementing this interface, annotate it
 * {@code @ConditionalOnProperty(prefix="economizaai.captcha", name="provider", havingValue="<name>")},
 * and set that name in config — nothing else changes.
 */
public interface CaptchaSolver {

    /** True when a real solver is wired (provider set + credentials present). */
    boolean isConfigured();

    /**
     * Solves a Google reCAPTCHA v2 ("I'm not a robot") and returns the
     * {@code g-recaptcha-response} token to submit back to the page.
     *
     * @throws com.relyon.economizaai.exception.CaptchaUnavailableException no solver configured
     * @throws com.relyon.economizaai.exception.CaptchaSolveFailedException solver ran but failed
     */
    String solveRecaptchaV2(String siteKey, String pageUrl);

    /**
     * Solves a Cloudflare Turnstile challenge and returns the
     * {@code cf-turnstile-response} token to submit back to the page.
     */
    default String solveCloudflareTurnstile(String siteKey, String pageUrl) {
        throw new CaptchaUnavailableException("captcha");
    }
}
