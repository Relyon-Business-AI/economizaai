package com.relyon.economizaai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cost-control knobs for paid external services (Infosimples, captcha solvers).
 * Every paid call is always logged for reconciliation; {@code enabled} gates the
 * enforcement (per-user daily caps + the Infosimples circuit breaker) so it can
 * be turned off wholesale without losing the ledger.
 */
@Configuration
@ConfigurationProperties(prefix = "economizaai.paid-api")
public class PaidApiGuardProperties {

    /** Master switch for enforcement (caps + breaker). Logging happens regardless. */
    private boolean enabled = true;

    /** Max Infosimples queries per user per day. 0 or negative = unlimited. */
    private int infosimplesDailyCapPerUser = 20;

    /** Max captcha solves per user per day. 0 or negative = unlimited. */
    private int captchaDailyCapPerUser = 60;

    /** Max Twilio SMS/WhatsApp messages per user per day. 0 or negative = unlimited. */
    private int twilioDailyCapPerUser = 10;

    /** Max LLM enrichment batch calls per day (global worker, keyed on the system user row = null). */
    private int llmEnrichDailyCapPerUser = 200;

    /** Max LLM vision photo-extractions per user per day. 0 or negative = unlimited. */
    private int llmVisionDailyCapPerUser = 5;

    /**
     * Global spend ceiling across ALL users per day, in cents (R$). The kill-switch
     * against a viral spike: once today's ledger total reaches this, every paid call
     * fails fast until midnight (UTC). 0 or negative = unlimited. Default R$50/day.
     */
    private int dailyGlobalBudgetCents = 5000;

    /** Consecutive/within-window Infosimples failures that trip the breaker. */
    private int infosimplesFailureThreshold = 5;

    /** Rolling window over which failures are counted toward the threshold. */
    private long breakerWindowSeconds = 600;

    /** How long the breaker stays open (calls fail fast) once tripped. */
    private long breakerCooldownSeconds = 300;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getInfosimplesDailyCapPerUser() { return infosimplesDailyCapPerUser; }
    public void setInfosimplesDailyCapPerUser(int infosimplesDailyCapPerUser) { this.infosimplesDailyCapPerUser = infosimplesDailyCapPerUser; }

    public int getCaptchaDailyCapPerUser() { return captchaDailyCapPerUser; }
    public void setCaptchaDailyCapPerUser(int captchaDailyCapPerUser) { this.captchaDailyCapPerUser = captchaDailyCapPerUser; }

    public int getTwilioDailyCapPerUser() { return twilioDailyCapPerUser; }
    public void setTwilioDailyCapPerUser(int twilioDailyCapPerUser) { this.twilioDailyCapPerUser = twilioDailyCapPerUser; }

    public int getLlmEnrichDailyCapPerUser() { return llmEnrichDailyCapPerUser; }
    public void setLlmEnrichDailyCapPerUser(int llmEnrichDailyCapPerUser) { this.llmEnrichDailyCapPerUser = llmEnrichDailyCapPerUser; }

    public int getLlmVisionDailyCapPerUser() { return llmVisionDailyCapPerUser; }
    public void setLlmVisionDailyCapPerUser(int llmVisionDailyCapPerUser) { this.llmVisionDailyCapPerUser = llmVisionDailyCapPerUser; }

    public int getDailyGlobalBudgetCents() { return dailyGlobalBudgetCents; }
    public void setDailyGlobalBudgetCents(int dailyGlobalBudgetCents) { this.dailyGlobalBudgetCents = dailyGlobalBudgetCents; }

    public int getInfosimplesFailureThreshold() { return infosimplesFailureThreshold; }
    public void setInfosimplesFailureThreshold(int infosimplesFailureThreshold) { this.infosimplesFailureThreshold = infosimplesFailureThreshold; }

    public long getBreakerWindowSeconds() { return breakerWindowSeconds; }
    public void setBreakerWindowSeconds(long breakerWindowSeconds) { this.breakerWindowSeconds = breakerWindowSeconds; }

    public long getBreakerCooldownSeconds() { return breakerCooldownSeconds; }
    public void setBreakerCooldownSeconds(long breakerCooldownSeconds) { this.breakerCooldownSeconds = breakerCooldownSeconds; }
}
