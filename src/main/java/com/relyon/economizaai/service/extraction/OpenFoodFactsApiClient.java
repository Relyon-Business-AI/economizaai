package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

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
    private static final String FIELDS =
            "code,product_name,brands,categories_tags,image_url,quantity,labels_tags,nutriscore_grade,nova_group";

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
    public Optional<OpenFoodFactsRow> fetch(String ean) {
        return Optional.ofNullable(lookup(ean).row());
    }

    /** As {@link #lookup(String, BooleanSupplier)} with no time budget. */
    public LookupResult lookup(String ean) {
        return lookup(ean, () -> false);
    }

    /**
     * Looks one barcode up across the OFF-family databases and says HOW it ended, so the caller
     * can tell "nobody knows this barcode" (safe to remember) from "we couldn't ask" (must be
     * asked again later):
     * <ul>
     *   <li>{@link LookupStatus#FOUND} — the first database that knows it (status == 1);</li>
     *   <li>{@link LookupStatus#NOT_FOUND} — EVERY database answered and none knows it;</li>
     *   <li>{@link LookupStatus#UNAVAILABLE} — at least one database gave no usable answer
     *       (timeout, 429, 5xx, empty body) or the time budget ran out before asking them all.</li>
     * </ul>
     * Never throws — a lookup failure must not break ingest. {@code budgetExpired} is checked
     * before each database so a slow API can't hold the caller past its budget.
     */
    public LookupResult lookup(String ean, BooleanSupplier budgetExpired) {
        if (!enabled || ean == null || ean.isBlank()) return LookupResult.unavailable();
        var everyDatabaseAnswered = true;
        for (var host : hosts) {
            if (budgetExpired.getAsBoolean()) {
                everyDatabaseAnswered = false;
                break;
            }
            try {
                var body = get(host.trim() + "/api/v2/product/" + ean + ".json?fields=" + FIELDS);
                if (body == null) {
                    everyDatabaseAnswered = false;
                    continue;
                }
                var row = toRow(ean, body);
                if (row != null) {
                    log.info("off.api.hit ean={} host={}", ean, host.trim());
                    return LookupResult.found(row);
                }
            } catch (HttpClientErrorException.NotFound unknownBarcode) {
                // The database answered: it has no such product. Keep asking the siblings.
            } catch (RuntimeException ex) {
                everyDatabaseAnswered = false;
                log.debug("off.api.error ean={} host={} reason={}", ean, host.trim(), ex.getClass().getSimpleName());
            }
        }
        return everyDatabaseAnswered ? LookupResult.notFound() : LookupResult.unavailable();
    }

    /** The catalog row for an answered lookup, or null when the database doesn't know the barcode. */
    private static OpenFoodFactsRow toRow(String ean, Map<String, Object> body) {
        var status = body.get("status");
        if (!(status instanceof Number number) || number.intValue() != 1) return null;
        if (!(body.get("product") instanceof Map<?, ?> product)) return null;
        return new OpenFoodFactsRow(
                ean,
                asString(product.get("product_name")),
                asString(product.get("brands")),
                joinTags(product.get("categories_tags")),
                asString(product.get("image_url")),
                asString(product.get("quantity")),
                joinTags(product.get("labels_tags")),
                asString(product.get("nutriscore_grade")),
                asInteger(product.get("nova_group")));
    }

    public enum LookupStatus { FOUND, NOT_FOUND, UNAVAILABLE }

    /** {@code row} is present only for {@link LookupStatus#FOUND}. */
    public record LookupResult(LookupStatus status, OpenFoodFactsRow row) {

        public static LookupResult found(OpenFoodFactsRow row) {
            return new LookupResult(LookupStatus.FOUND, row);
        }

        public static LookupResult notFound() {
            return new LookupResult(LookupStatus.NOT_FOUND, null);
        }

        public static LookupResult unavailable() {
            return new LookupResult(LookupStatus.UNAVAILABLE, null);
        }
    }

    /** HTTP seam — isolated so the host-fallback + parsing logic is unit-testable. */
    protected Map<String, Object> get(String url) {
        return restClient.get().uri(url).retrieve().body(MAP_TYPE);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** OFF tag fields come as a JSON array; flatten to a comma-joined string. */
    private static String joinTags(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
