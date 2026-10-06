package com.relyon.economizaai.service.analytics.gsc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Google Search Console API config. Inert until {@link #isConfigured()} —
 * enabled=true AND a non-blank access token. Activate via env vars
 * GOOGLE_SC_ENABLED=true + GOOGLE_SC_ACCESS_TOKEN.
 */
@Configuration
@ConfigurationProperties(prefix = "google.search-console")
public class GoogleSearchConsoleProperties {

    private boolean enabled = false;
    private String accessToken = "";
    private String siteUrl = "sc-domain:economizaai.app";
    private String apiVersion = "v3";

    public boolean isConfigured() {
        return enabled && accessToken != null && !accessToken.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getSiteUrl() { return siteUrl; }
    public void setSiteUrl(String siteUrl) { this.siteUrl = siteUrl; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
}
