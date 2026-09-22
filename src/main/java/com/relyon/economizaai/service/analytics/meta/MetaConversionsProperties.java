package com.relyon.economizaai.service.analytics.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the Meta Conversions API (server-side event send). INERT until
 * {@link #isConfigured()} — needs the Pixel/dataset id + a Conversions API access
 * token (generated per-Pixel in Events Manager, separate from the ads token).
 * With this on, signups are reported server-side and deduplicated with the
 * browser Pixel via {@code event_id}, so the data survives ad-blockers / iOS ITP.
 */
@Component
@ConfigurationProperties(prefix = "meta.capi")
public class MetaConversionsProperties {

    private boolean enabled = false;
    private String pixelId;
    private String token;
    private String apiVersion = "v21.0";
    private String graphBaseUrl = "https://graph.facebook.com";
    /** Optional test-event code (Events Manager → Test Events) to verify without polluting live data. */
    private String testEventCode;

    public boolean isConfigured() {
        return enabled
                && pixelId != null && !pixelId.isBlank()
                && token != null && !token.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getPixelId() { return pixelId; }
    public void setPixelId(String pixelId) { this.pixelId = pixelId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    public String getGraphBaseUrl() { return graphBaseUrl; }
    public void setGraphBaseUrl(String graphBaseUrl) { this.graphBaseUrl = graphBaseUrl; }

    public String getTestEventCode() { return testEventCode; }
    public void setTestEventCode(String testEventCode) { this.testEventCode = testEventCode; }
}
