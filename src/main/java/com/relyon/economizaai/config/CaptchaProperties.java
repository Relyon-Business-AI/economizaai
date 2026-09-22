package com.relyon.economizaai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Captcha-solver config. Default {@code provider=none} → no solver active →
 * captcha-gated states (MS/SC) fail fast with a clear "not enabled" error. Pick a
 * provider by setting {@code economizaai.captcha.provider} + {@code api-key};
 * {@code capsolver} supports reCAPTCHA v2 and Turnstile; {@code twocaptcha}
 * supports the MS reCAPTCHA-v2 flow. See DEV_NOTES
 * "Captcha solver".
 */
@Configuration
@ConfigurationProperties(prefix = "economizaai.captcha")
public class CaptchaProperties {

    /** none | twocaptcha | capsolver (add more by implementing CaptchaSolver with its own @ConditionalOnProperty). */
    private String provider = "none";
    private String apiKey = "";
    /** Optional endpoint override for 2Captcha-compatible providers (CapMonster, etc). */
    private String baseUrl = "https://2captcha.com";
    /** Overall solve budget; a v2 solve typically takes 15-40s. */
    private long timeoutMs = 120000;
    private long pollIntervalMs = 5000;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
}
