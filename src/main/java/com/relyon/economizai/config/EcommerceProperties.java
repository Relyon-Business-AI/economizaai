package com.relyon.economizai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Config for the e-commerce price-comparison subsystem. Everything is inert until
 * a provider is configured — the MVP serves admin-CURATED offers (precision first),
 * and each e-commerce is a pluggable provider block here. Adding a new e-commerce =
 * add a {@code providers.<key>} block (env vars) + a new EcommerceProvider bean.
 *
 * <p>{@code master} {@link #enabled} gates the whole feature; each provider also has
 * its own {@code enabled} + credentials so they light up one at a time.
 */
@Component
@ConfigurationProperties(prefix = "economizai.ecommerce")
public class EcommerceProperties {

    /** Master switch for the whole e-commerce feature (offers still work via curation when providers are off). */
    private boolean enabled = false;

    /** Minimum R$ saved (online total vs what the user paid) to flag an offer as "vale a pena". */
    private BigDecimal worthItMinSavings = BigDecimal.ZERO;

    /** Fallback CEP used for freight estimates when the user's is unknown (optional). */
    private String defaultCep = "";

    /** One block per e-commerce, keyed by a short provider key (e.g. "mercadolivre"). */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public Optional<Provider> provider(String key) {
        return Optional.ofNullable(providers.get(key));
    }

    /**
     * Credentials/settings for a single e-commerce. Fields are a superset — each
     * provider uses the subset it needs (ML: clientId/clientSecret for the catalog
     * API + affiliateTag or the third-party affiliateApi* for monetized links).
     */
    public static class Provider {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String affiliateTag = "";
        private String affiliateApiKey = "";
        private String affiliateApiUrl = "";
        private String siteId = "";
        private String baseUrl = "";

        /** True when this provider can actually call its catalog API. */
        public boolean isConfigured() {
            return enabled
                    && clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getAffiliateTag() { return affiliateTag; }
        public void setAffiliateTag(String affiliateTag) { this.affiliateTag = affiliateTag; }
        public String getAffiliateApiKey() { return affiliateApiKey; }
        public void setAffiliateApiKey(String affiliateApiKey) { this.affiliateApiKey = affiliateApiKey; }
        public String getAffiliateApiUrl() { return affiliateApiUrl; }
        public void setAffiliateApiUrl(String affiliateApiUrl) { this.affiliateApiUrl = affiliateApiUrl; }
        public String getSiteId() { return siteId; }
        public void setSiteId(String siteId) { this.siteId = siteId; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public BigDecimal getWorthItMinSavings() { return worthItMinSavings; }
    public void setWorthItMinSavings(BigDecimal worthItMinSavings) { this.worthItMinSavings = worthItMinSavings; }
    public String getDefaultCep() { return defaultCep; }
    public void setDefaultCep(String defaultCep) { this.defaultCep = defaultCep; }
    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }
}
