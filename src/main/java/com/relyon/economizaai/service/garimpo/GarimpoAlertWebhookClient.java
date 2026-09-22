package com.relyon.economizaai.service.garimpo;

import com.relyon.economizaai.config.GarimpoProperties;
import com.relyon.economizaai.model.GarimpoWatch;
import com.relyon.economizaai.service.ecommerce.ProviderProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Dispatches watch hits to the configured webhook (a group bot, Zapier, n8n…) as a
 * single JSON POST per run. Inert without a URL; guarded — a webhook failure is logged
 * and never breaks the watch run (the hits are already in the price history).
 */
@Slf4j
@Component
public class GarimpoAlertWebhookClient {

    private final GarimpoProperties properties;
    private final RestClient restClient;

    record AlertPayload(UUID watchId, String searchTerm, String marketplace, List<AlertHit> hits) {
    }

    record AlertHit(String title, BigDecimal price, BigDecimal originalPrice, Integer discountPercent,
                    String url, String imageUrl, boolean freeShipping) {
    }

    public GarimpoAlertWebhookClient(GarimpoProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    /** True when the alert was actually POSTed and accepted. */
    public boolean notifyHits(GarimpoWatch watch, List<ProviderProduct> hits) {
        if (!properties.hasWebhook()) {
            log.info("garimpo.alert.skipped_no_webhook watch={} hits={}", abbrev(watch.getId()), hits.size());
            return false;
        }
        try {
            restClient.post()
                    .uri(properties.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(watch, hits))
                    .retrieve()
                    .toBodilessEntity();
            log.info("garimpo.alert.sent watch={} term={} hits={}",
                    abbrev(watch.getId()), watch.getSearchTerm(), hits.size());
            return true;
        } catch (Exception exception) {
            log.warn("garimpo.alert.failed watch={} error={}", abbrev(watch.getId()), exception.getMessage());
            return false;
        }
    }

    private AlertPayload payload(GarimpoWatch watch, List<ProviderProduct> hits) {
        var alertHits = hits.stream()
                .map(product -> new AlertHit(
                        product.title(),
                        product.price(),
                        product.originalPrice(),
                        product.discountPercent(),
                        product.affiliateUrl() != null ? product.affiliateUrl() : product.externalUrl(),
                        product.imageUrl(),
                        product.freeShipping()))
                .toList();
        return new AlertPayload(watch.getId(), watch.getSearchTerm(), watch.getProvider(), alertHits);
    }

    private static String abbrev(UUID id) {
        return id == null ? null : id.toString().substring(0, 8);
    }
}
