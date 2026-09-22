package com.relyon.economizaai.service.analytics.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Meta (Facebook/Instagram) Marketing API ad-spend config. The whole feature is
 * INERT until {@link #isConfigured()} — {@code enabled=true} AND a non-blank
 * token AND ad account id. With defaults ({@code enabled=false}) the sync job
 * skips and no outbound call is ever made. Activate by setting the env vars in
 * {@code application.yaml}'s {@code meta.ads} block.
 */
@Configuration
@ConfigurationProperties(prefix = "meta.ads")
public class MetaAdsProperties {

    private boolean enabled = false;
    private String token = "";
    private String adAccountId = "";
    private String apiVersion = "v21.0";
    private int syncDays = 30;
    private String graphBaseUrl = "https://graph.facebook.com";

    /** True only when the integration is enabled AND fully credentialed. */
    public boolean isConfigured() {
        return enabled
                && token != null && !token.isBlank()
                && adAccountId != null && !adAccountId.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getAdAccountId() { return adAccountId; }
    public void setAdAccountId(String adAccountId) { this.adAccountId = adAccountId; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    public int getSyncDays() { return syncDays; }
    public void setSyncDays(int syncDays) { this.syncDays = syncDays; }

    public String getGraphBaseUrl() { return graphBaseUrl; }
    public void setGraphBaseUrl(String graphBaseUrl) { this.graphBaseUrl = graphBaseUrl; }
}
