package com.relyon.economizai.service.ecommerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.relyon.economizai.config.EcommerceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Mercado Livre provider. Catalog/price via the official ML Developer API (OAuth2
 * client-credentials → Bearer token, then the site items search by EAN). Affiliate
 * links: ML has NO official affiliate API, so the monetized link is best-effort
 * (append the configured tag, or hand off to a third-party link API if set).
 *
 * <p>INERT until {@code economizai.ecommerce.providers.mercadolivre} has enabled +
 * clientId + clientSecret. When not configured, {@link #searchByEan} returns empty
 * without any network call. The live path is guarded (never throws) and must be
 * verified once real credentials land — see DEV_NOTES.
 */
@Slf4j
@Component
public class MercadoLivreProvider implements EcommerceProvider {

    static final String KEY = "mercadolivre";
    private static final int MAX_RESULTS = 3;

    private final EcommerceProperties properties;
    private final RestClient restClient;

    public MercadoLivreProvider(EcommerceProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(15000);
        this.restClient = builder
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.provider(KEY).map(EcommerceProperties.Provider::isConfigured).orElse(false);
    }

    @Override
    public List<ProviderOffer> searchByEan(String ean, String cep) {
        if (!isConfigured() || ean == null || ean.isBlank()) {
            return List.of();
        }
        var config = properties.provider(KEY).orElseThrow();
        try {
            var token = fetchAccessToken(config);
            if (token == null) {
                return List.of();
            }
            var site = config.getSiteId() == null || config.getSiteId().isBlank() ? "MLB" : config.getSiteId();
            var body = restClient.get()
                    .uri(config.getBaseUrl() + "/sites/{site}/search?q={ean}&limit={limit}", site, ean, MAX_RESULTS)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JsonNode.class);
            return mapResults(body, config);
        } catch (Exception exception) {
            // Inert-by-design: a provider failure must never break the offer lookup.
            log.warn("ecommerce.mercadolivre.search_failed ean={} error={}", ean, exception.getMessage());
            return List.of();
        }
    }

    private String fetchAccessToken(EcommerceProperties.Provider config) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", config.getClientId());
        form.add("client_secret", config.getClientSecret());
        var response = restClient.post()
                .uri(config.getBaseUrl() + "/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        var token = response == null ? null : response.path("access_token").asText(null);
        return token == null || token.isBlank() ? null : token;
    }

    private List<ProviderOffer> mapResults(JsonNode body, EcommerceProperties.Provider config) {
        var offers = new ArrayList<ProviderOffer>();
        if (body == null || !body.has("results")) {
            return offers;
        }
        for (var result : body.get("results")) {
            var price = result.path("price").isNumber() ? result.path("price").decimalValue() : null;
            if (price == null) {
                continue;
            }
            var permalink = result.path("permalink").asText(null);
            var freeShipping = result.path("shipping").path("free_shipping").asBoolean(false);
            offers.add(new ProviderOffer(
                    KEY,
                    result.path("title").asText(""),
                    price,
                    freeShipping ? BigDecimal.ZERO : null, // paid freight needs a per-item shipping call — omitted for now
                    result.path("currency_id").asText("BRL"),
                    permalink,
                    affiliateUrl(permalink, config),
                    result.path("thumbnail").asText(null),
                    result.path("available_quantity").asInt(1) > 0));
        }
        return offers;
    }

    /**
     * Best-effort monetized link. With a third-party link API configured, that would
     * convert the permalink; otherwise we append the affiliate tag. The exact ML
     * affiliate link format must be confirmed live before trusting the commission.
     */
    private String affiliateUrl(String permalink, EcommerceProperties.Provider config) {
        if (permalink == null || config.getAffiliateTag() == null || config.getAffiliateTag().isBlank()) {
            return permalink;
        }
        var separator = permalink.contains("?") ? "&" : "?";
        return permalink + separator + "matt_tool=" + config.getAffiliateTag();
    }
}
