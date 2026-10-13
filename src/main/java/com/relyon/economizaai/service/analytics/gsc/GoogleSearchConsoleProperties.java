package com.relyon.economizaai.service.analytics.gsc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Google Search Console API config. Inert until {@link #isConfigured()} —
 * enabled=true AND an auth source. Two auth modes:
 * <ul>
 *   <li><b>Service account (preferred, self-renewing):</b> GOOGLE_SC_SA_CLIENT_EMAIL +
 *       GOOGLE_SC_SA_PRIVATE_KEY — a token is minted on demand and cached until expiry,
 *       so it never goes stale (see {@link GoogleServiceAccountTokenProvider}).</li>
 *   <li><b>Static access token (legacy):</b> GOOGLE_SC_ACCESS_TOKEN — a raw OAuth2 token
 *       that Google expires after ~1h, so the report breaks once it lapses.</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "google.search-console")
public class GoogleSearchConsoleProperties {

    private boolean enabled = false;
    private String accessToken = "";
    private String saClientEmail = "";
    private String saPrivateKey = "";
    private String siteUrl = "sc-domain:economizaai.app";
    private String apiVersion = "v3";

    public boolean isConfigured() {
        return enabled && (hasServiceAccount() || hasStaticToken());
    }

    /** Service-account auth is set up — preferred over the static token when both exist. */
    public boolean hasServiceAccount() {
        return saClientEmail != null && !saClientEmail.isBlank()
                && saPrivateKey != null && !saPrivateKey.isBlank();
    }

    public boolean hasStaticToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getSaClientEmail() { return saClientEmail; }
    public void setSaClientEmail(String saClientEmail) { this.saClientEmail = saClientEmail; }

    public String getSaPrivateKey() { return saPrivateKey; }
    public void setSaPrivateKey(String saPrivateKey) { this.saPrivateKey = saPrivateKey; }

    public String getSiteUrl() { return siteUrl; }
    public void setSiteUrl(String siteUrl) { this.siteUrl = siteUrl; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
}
