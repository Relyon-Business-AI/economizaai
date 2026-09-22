package com.relyon.economizaai.service.ecommerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.relyon.economizaai.config.EcommerceProperties;
import com.relyon.economizaai.exception.EcommerceProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mercado Livre provider. Catalog/price via the official ML Developer API (OAuth2
 * client-credentials → Bearer token, then the site items search by EAN or free term).
 * Affiliate links: ML has NO official affiliate API, so the monetized link is
 * best-effort (append the configured tag, or hand off to a third-party link API if set).
 *
 * <p>INERT until {@code economizaai.ecommerce.providers.mercadolivre} has enabled +
 * clientId + clientSecret. When not configured, both searches return empty without any
 * network call. The public (tokenless) search API returns 403 as of 2026-09 — every
 * live call authenticates. Tokens are cached until shortly before expiry.
 *
 * <p>Error posture differs per flow: {@link #searchByEan} is on the receipt path and
 * never throws; {@link #searchByTerm} is the garimpo/admin path and throws
 * {@link EcommerceProviderException} on live failures so outages aren't mistaken for
 * "no results".
 */
@Slf4j
@Component
public class MercadoLivreProvider implements EcommerceProvider {

    static final String KEY = "mercadolivre";
    private static final int MAX_RESULTS = 3;
    private static final long TOKEN_EXPIRY_SAFETY_SECONDS = 60;

    private final EcommerceProperties properties;
    private final RestClient restClient;

    private volatile CachedToken cachedToken;

    private record CachedToken(String value, Instant expiresAt) {
        boolean stillValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

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
            var body = searchRequest(config, ean, 0, MAX_RESULTS);
            return mapOffers(body, config);
        } catch (Exception exception) {
            // Inert-by-design: a provider failure must never break the offer lookup.
            log.warn("ecommerce.mercadolivre.search_failed ean={} error={}", ean, exception.getMessage());
            return List.of();
        }
    }

    @Override
    public ProviderSearchResult searchByTerm(String term, int offset, int limit) {
        if (!isConfigured() || term == null || term.isBlank()) {
            return ProviderSearchResult.empty();
        }
        var config = properties.provider(KEY).orElseThrow();
        try {
            var body = searchRequest(config, term, offset, limit);
            return mapProducts(body, config);
        } catch (EcommerceProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new EcommerceProviderException(
                    "Mercado Livre term search failed: " + exception.getMessage(), exception);
        }
    }

    private JsonNode searchRequest(EcommerceProperties.Provider config, String query, int offset, int limit) {
        var token = cachedAccessToken(config);
        if (token == null) {
            throw new EcommerceProviderException("Mercado Livre token grant returned no access_token", null);
        }
        var site = config.getSiteId() == null || config.getSiteId().isBlank() ? "MLB" : config.getSiteId();
        return restClient.get()
                .uri(config.getBaseUrl() + "/sites/{site}/search?q={query}&offset={offset}&limit={limit}",
                        site, query, offset, limit)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Client-credentials token, cached until shortly before ML's reported expiry. */
    private String cachedAccessToken(EcommerceProperties.Provider config) {
        var cached = cachedToken;
        if (cached != null && cached.stillValid()) {
            return cached.value();
        }
        synchronized (this) {
            cached = cachedToken;
            if (cached != null && cached.stillValid()) {
                return cached.value();
            }
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
            if (token == null || token.isBlank()) {
                return null;
            }
            var expiresInSeconds = response.path("expires_in").asLong(21600);
            cachedToken = new CachedToken(
                    token,
                    Instant.now().plusSeconds(Math.max(expiresInSeconds - TOKEN_EXPIRY_SAFETY_SECONDS, 30)));
            return token;
        }
    }

    private List<ProviderOffer> mapOffers(JsonNode body, EcommerceProperties.Provider config) {
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

    ProviderSearchResult mapProducts(JsonNode body, EcommerceProperties.Provider config) {
        if (body == null || !body.has("results")) {
            return ProviderSearchResult.empty();
        }
        var products = new ArrayList<ProviderProduct>();
        for (var result : body.get("results")) {
            var price = result.path("price").isNumber() ? result.path("price").decimalValue() : null;
            if (price == null) {
                continue;
            }
            var originalPrice = result.path("original_price").isNumber()
                    ? result.path("original_price").decimalValue()
                    : null;
            var permalink = result.path("permalink").asText(null);
            products.add(new ProviderProduct(
                    KEY,
                    result.path("id").asText(null),
                    result.path("title").asText(""),
                    price,
                    originalPrice,
                    discountPercent(price, originalPrice),
                    result.path("currency_id").asText("BRL"),
                    permalink,
                    affiliateUrl(permalink, config),
                    result.path("thumbnail").asText(null),
                    result.path("seller").path("nickname").asText(null),
                    result.path("shipping").path("free_shipping").asBoolean(false)));
        }
        var total = body.path("paging").path("total").asInt(products.size());
        return new ProviderSearchResult(total, products);
    }

    private Integer discountPercent(BigDecimal price, BigDecimal originalPrice) {
        if (originalPrice == null
                || originalPrice.compareTo(BigDecimal.ZERO) <= 0
                || originalPrice.compareTo(price) <= 0) {
            return null;
        }
        return originalPrice.subtract(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(originalPrice, 0, RoundingMode.HALF_UP)
                .intValue();
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
