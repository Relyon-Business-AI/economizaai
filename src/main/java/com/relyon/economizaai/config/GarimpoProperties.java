package com.relyon.economizaai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the garimpo (deal-hunting) subsystem: term searches, price history and
 * scheduled watches on top of the e-commerce providers. Provider credentials live in
 * {@link EcommerceProperties} — garimpo only works against configured providers.
 * With no webhook URL, watch hits are logged but not dispatched (log-only mode).
 */
@Component
@ConfigurationProperties(prefix = "economizaai.garimpo")
public class GarimpoProperties {

    /** Master switch for the watch sweeper (search/history endpoints stay available). */
    private boolean enabled = true;

    /** Where watch hits are POSTed as JSON (a group bot, Zapier, n8n…). Blank = log-only. */
    private String webhookUrl = "";

    /** Upper bound for search page size (mirrors the ML API's own 50 cap). */
    private int searchMaxLimit = 50;

    public boolean hasWebhook() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public int getSearchMaxLimit() { return searchMaxLimit; }
    public void setSearchMaxLimit(int searchMaxLimit) { this.searchMaxLimit = searchMaxLimit; }
}
