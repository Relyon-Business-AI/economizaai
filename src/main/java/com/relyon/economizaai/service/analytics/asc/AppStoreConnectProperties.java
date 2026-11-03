package com.relyon.economizaai.service.analytics.asc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * App Store Connect API credentials for fetching store analytics (impressions,
 * page views, installs by source). Inert until {@link #isConfigured()} returns true.
 *
 * <p>Required env vars on Render:
 * <ul>
 *   <li>APPLE_ASC_ENABLED=true</li>
 *   <li>APPLE_ASC_PRIVATE_KEY — full PEM contents of ~/.config/appstore/economizai.p8</li>
 * </ul>
 * Key ID and Issuer ID default to the project's known values (non-secret identifiers).
 */
@Configuration
@ConfigurationProperties(prefix = "apple.asc")
public class AppStoreConnectProperties {

    private boolean enabled = false;
    private String appId = "6788025621";
    private String issuerId = "8a41f4c7-c2b0-4afd-9dc7-0e9ee3ccd39e";
    private String keyId = "B3SKVUKUPS";
    /** PEM contents of the .p8 private key file (set via APPLE_ASC_PRIVATE_KEY env var). */
    private String privateKey = "";
    private int syncDays = 90;

    public boolean isConfigured() {
        return enabled && privateKey != null && !privateKey.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getIssuerId() { return issuerId; }
    public void setIssuerId(String issuerId) { this.issuerId = issuerId; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }

    public int getSyncDays() { return syncDays; }
    public void setSyncDays(int syncDays) { this.syncDays = syncDays; }
}
