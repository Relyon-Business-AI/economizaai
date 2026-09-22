package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live lookup of a barcode against the Open Food Facts family of open databases,
 * used as a fallback when our imported catalog has no entry for an EAN. Queries
 * three sibling projects in turn so ANY product type is covered:
 * <ol>
 *   <li><b>Open Food Facts</b> — food/beverages,</li>
 *   <li><b>Open Beauty Facts</b> — hygiene/cosmetics,</li>
 *   <li><b>Open Products Facts</b> — everything else (cleaning, etc.).</li>
 * </ol>
 * The first project that knows the barcode wins. Results are cached-through into
 * our own catalog by {@link EanCatalogEnrichmentService}, so each barcode is
 * fetched at most once.
 *
 * <p>OFF-family barcodes are global, so this transparently covers Brazilian,
 * American, Argentine, … products alike. OFF's fair-use policy asks for a
 * descriptive User-Agent and modest rates; we only call it for barcodes we've
 * never seen, off the request thread.
 */
@Slf4j
@Component
public class OpenFoodFactsApiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final String FIELDS = "code,product_name,brands,categories_tags";

    private final RestClient restClient;
    private final boolean enabled;
    private final List<String> hosts;

    public OpenFoodFactsApiClient(
            RestClient.Builder builder,
            @Value("${economizaai.ingestion.ean-catalog.live-fallback-enabled:false}") boolean enabled,
            @Value("${economizaai.ingestion.ean-catalog.live-fallback-timeout-ms:2500}") int timeoutMs,
            @Value("${economizaai.ingestion.ean-catalog.live-fallback-hosts:https://world.openfoodfacts.org,https://world.openbeautyfacts.org,https://world.openproductsfacts.org}") String hostsCsv,
            @Value("${economizaai.ingestion.ean-catalog.live-fallback-user-agent:economizai/1.0 (+https://github.com/XandiVieira/economiz.AI)}") String userAgent) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 3000));
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = builder
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        this.enabled = enabled;
        this.hosts = List.of(hostsCsv.split(","));
        log.info("OpenFoodFactsApiClient live-fallback enabled={} hosts={}", enabled, this.hosts);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Fetches one barcode across the OFF-family databases. Returns the first hit
     * (status == 1) as a catalog-import row, or empty when no database knows it
     * or every call errors. Never throws — a lookup failure must not break ingest.
     */
    @SuppressWarnings("unchecked")
    public Optional<OpenFoodFactsRow> fetch(String ean) {
        if (!enabled || ean == null || ean.isBlank()) return Optional.empty();
        for (var host : hosts) {
            try {
                var url = host.trim() + "/api/v2/product/" + ean + ".json?fields=" + FIELDS;
                var body = get(url);
                if (body == null) continue;
                var status = body.get("status");
                if (!(status instanceof Number number) || number.intValue() != 1) continue;
                if (!(body.get("product") instanceof Map<?, ?> product)) continue;
                var tags = product.get("categories_tags");
                var categoryTags = tags instanceof List<?> list
                        ? String.join(",", list.stream().map(String::valueOf).toList())
                        : String.valueOf(tags == null ? "" : tags);
                log.info("off.api.hit ean={} host={}", ean, host.trim());
                return Optional.of(new OpenFoodFactsRow(
                        ean,
                        asString(product.get("product_name")),
                        asString(product.get("brands")),
                        categoryTags));
            } catch (RuntimeException ex) {
                log.debug("off.api.error ean={} host={} reason={}", ean, host.trim(), ex.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    /** HTTP seam — isolated so the host-fallback + parsing logic is unit-testable. */
    protected Map<String, Object> get(String url) {
        return restClient.get().uri(url).retrieve().body(MAP_TYPE);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
