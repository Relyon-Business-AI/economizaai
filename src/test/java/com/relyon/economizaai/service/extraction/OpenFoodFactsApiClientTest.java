package com.relyon.economizaai.service.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFoodFactsApiClientTest {

    private static final String HOSTS = "https://off,https://obf,https://opf";

    /** Client whose HTTP seam returns canned JSON maps keyed by host substring. */
    private static class StubClient extends OpenFoodFactsApiClient {
        private final Map<String, Map<String, Object>> byHost;
        StubClient(boolean enabled, Map<String, Map<String, Object>> byHost) {
            super(RestClient.builder(), enabled, 2000, HOSTS, "test-ua");
            this.byHost = byHost;
        }
        @Override
        protected Map<String, Object> get(String url) {
            for (var entry : byHost.entrySet()) {
                if (url.contains(entry.getKey())) return entry.getValue();
            }
            return null;
        }
    }

    private static Map<String, Object> found(String name, String brand, List<String> tags) {
        return Map.of("status", 1, "product",
                Map.of("product_name", name, "brands", brand, "categories_tags", tags));
    }

    private static final Map<String, Object> NOT_FOUND = Map.of("status", 0);

    @Test
    void fetch_returnsFirstHost_hit() {
        var client = new StubClient(true, Map.of(
                "off", found("Neston", "Nestlé", List.of("pt:cereais"))));
        var row = client.fetch("7891000098950").orElseThrow();
        assertEquals("7891000098950", row.code());
        assertEquals("Neston", row.productName());
        assertEquals("Nestlé", row.brands());
        assertEquals("pt:cereais", row.categoryTags());
    }

    @Test
    void fetch_fallsThroughToBeautyDb_whenFoodMisses() {
        var client = new StubClient(true, Map.of(
                "off", NOT_FOUND,
                "obf", found("Listerine", "J&J", List.of("en:mouthwashes"))));
        var row = client.fetch("7891010256043").orElseThrow();
        assertEquals("Listerine", row.productName());
        assertEquals("en:mouthwashes", row.categoryTags());
    }

    @Test
    void fetch_emptyWhenNoDbKnowsIt() {
        var client = new StubClient(true, Map.of(
                "off", NOT_FOUND, "obf", NOT_FOUND, "opf", NOT_FOUND));
        assertTrue(client.fetch("0000000000000").isEmpty());
    }

    @Test
    void fetch_emptyWhenDisabled_withoutCallingHttp() {
        var client = new StubClient(false, Map.of("off", found("X", "Y", List.of("en:cereals"))));
        assertFalse(client.isEnabled());
        assertTrue(client.fetch("7891000098950").isEmpty());
    }
}
