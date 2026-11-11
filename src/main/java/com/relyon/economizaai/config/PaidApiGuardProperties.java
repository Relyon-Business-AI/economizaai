package com.relyon.economizaai.config;

import com.relyon.economizaai.model.enums.PaidApiService;
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
    private int infosimplesDailyCapPerUser = 10;

    /** Max captcha solves per user per day. 0 or negative = unlimited. */
    private int captchaDailyCapPerUser = 30;

    /** Max Twilio SMS/WhatsApp messages per user per day. 0 or negative = unlimited. */
    private int twilioDailyCapPerUser = 3;

    /** Max AI categorization calls (suggestion, item fallback, correction) per user per day. 0 or negative = unlimited. */
    private int llmEnrichDailyCapPerUser = 60;

    /** Max LLM vision photo-extractions per user per day (counted PER PHOTO — a
     *  multi-photo nota of 5 fotos consome 5 do cap). 0 or negative = unlimited. */
    private int llmVisionDailyCapPerUser = 10;

    /** Max LLM HTML parse-rescues per user per day. 0 or negative = unlimited. */
    private int llmHtmlRescueDailyCapPerUser = 5;

    /**
     * Daily spend ceiling PER SERVICE across all users, in cents (R$). Once today's
     * ledger total for a service reaches its ceiling only THAT service fails fast
     * until midnight (UTC); the others keep working. 0 or negative = unlimited.
     */
    private int infosimplesDailyBudgetCents = 12000;
    private int captchaDailyBudgetCents = 3000;
    private int llmVisionDailyBudgetCents = 3000;
    private int llmHtmlRescueDailyBudgetCents = 1500;
    private int llmEnrichDailyBudgetCents = 5000;
    private int twilioDailyBudgetCents = 1500;

    /**
     * Optional overall ceiling across ALL services per day, in cents (R$), on top of
     * the per-service ones. 0 or negative = off (the default).
     */
    private int dailyGlobalBudgetCents = 0;

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

    public int getLlmHtmlRescueDailyCapPerUser() { return llmHtmlRescueDailyCapPerUser; }
    public void setLlmHtmlRescueDailyCapPerUser(int llmHtmlRescueDailyCapPerUser) { this.llmHtmlRescueDailyCapPerUser = llmHtmlRescueDailyCapPerUser; }

    /** The daily ceiling (cents) configured for one service; 0 or negative = unlimited. */
    public int dailyBudgetCents(PaidApiService service) {
        return switch (service) {
            case INFOSIMPLES -> infosimplesDailyBudgetCents;
            case CAPTCHA_SOLVE -> captchaDailyBudgetCents;
            case TWILIO_MESSAGE -> twilioDailyBudgetCents;
            case LLM_ENRICH -> llmEnrichDailyBudgetCents;
            case LLM_VISION -> llmVisionDailyBudgetCents;
            case LLM_HTML_RESCUE -> llmHtmlRescueDailyBudgetCents;
        };
    }

    public int getInfosimplesDailyBudgetCents() { return infosimplesDailyBudgetCents; }
    public void setInfosimplesDailyBudgetCents(int infosimplesDailyBudgetCents) { this.infosimplesDailyBudgetCents = infosimplesDailyBudgetCents; }

    public int getCaptchaDailyBudgetCents() { return captchaDailyBudgetCents; }
    public void setCaptchaDailyBudgetCents(int captchaDailyBudgetCents) { this.captchaDailyBudgetCents = captchaDailyBudgetCents; }

    public int getLlmVisionDailyBudgetCents() { return llmVisionDailyBudgetCents; }
    public void setLlmVisionDailyBudgetCents(int llmVisionDailyBudgetCents) { this.llmVisionDailyBudgetCents = llmVisionDailyBudgetCents; }

    public int getLlmHtmlRescueDailyBudgetCents() { return llmHtmlRescueDailyBudgetCents; }
    public void setLlmHtmlRescueDailyBudgetCents(int llmHtmlRescueDailyBudgetCents) { this.llmHtmlRescueDailyBudgetCents = llmHtmlRescueDailyBudgetCents; }

    public int getLlmEnrichDailyBudgetCents() { return llmEnrichDailyBudgetCents; }
    public void setLlmEnrichDailyBudgetCents(int llmEnrichDailyBudgetCents) { this.llmEnrichDailyBudgetCents = llmEnrichDailyBudgetCents; }

    public int getTwilioDailyBudgetCents() { return twilioDailyBudgetCents; }
    public void setTwilioDailyBudgetCents(int twilioDailyBudgetCents) { this.twilioDailyBudgetCents = twilioDailyBudgetCents; }

    public int getDailyGlobalBudgetCents() { return dailyGlobalBudgetCents; }
    public void setDailyGlobalBudgetCents(int dailyGlobalBudgetCents) { this.dailyGlobalBudgetCents = dailyGlobalBudgetCents; }

    public int getInfosimplesFailureThreshold() { return infosimplesFailureThreshold; }
    public void setInfosimplesFailureThreshold(int infosimplesFailureThreshold) { this.infosimplesFailureThreshold = infosimplesFailureThreshold; }

    public long getBreakerWindowSeconds() { return breakerWindowSeconds; }
    public void setBreakerWindowSeconds(long breakerWindowSeconds) { this.breakerWindowSeconds = breakerWindowSeconds; }

    public long getBreakerCooldownSeconds() { return breakerCooldownSeconds; }
    public void setBreakerCooldownSeconds(long breakerCooldownSeconds) { this.breakerCooldownSeconds = breakerCooldownSeconds; }
}
