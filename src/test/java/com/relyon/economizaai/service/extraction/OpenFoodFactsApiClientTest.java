package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.service.extraction.OpenFoodFactsApiClient.LookupStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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

    /** Client whose HTTP seam fails for the hosts listed in {@code failing}. */
    private static class FailingClient extends OpenFoodFactsApiClient {
        private final Map<String, RuntimeException> failing;
        FailingClient(Map<String, RuntimeException> failing) {
            super(RestClient.builder(), true, 2000, HOSTS, "test-ua");
            this.failing = failing;
        }
        @Override
        protected Map<String, Object> get(String url) {
            for (var entry : failing.entrySet()) {
                if (url.contains(entry.getKey())) throw entry.getValue();
            }
            return NOT_FOUND;
        }
    }

    @Test
    void lookup_notFound_onlyWhenEveryDatabaseAnswered() {
        var client = new StubClient(true, Map.of("off", NOT_FOUND, "obf", NOT_FOUND, "opf", NOT_FOUND));

        assertEquals(LookupStatus.NOT_FOUND, client.lookup("0000000000000").status());
    }

    @Test
    void lookup_http404_countsAsARealNotFoundAnswer() {
        var client = new FailingClient(Map.of(
                "off", HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null)));

        assertEquals(LookupStatus.NOT_FOUND, client.lookup("0000000000000").status());
    }

    @Test
    void lookup_timeoutOrThrottleOrServerError_isUnavailableNotAMiss() {
        var timeout = new FailingClient(Map.of("obf", new ResourceAccessException("read timed out")));
        var throttled = new FailingClient(Map.of(
                "off", HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", HttpHeaders.EMPTY, null, null)));
        var serverError = new FailingClient(Map.of(
                "opf", HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, null, null)));

        assertEquals(LookupStatus.UNAVAILABLE, timeout.lookup("0000000000000").status());
        assertEquals(LookupStatus.UNAVAILABLE, throttled.lookup("0000000000000").status());
        assertEquals(LookupStatus.UNAVAILABLE, serverError.lookup("0000000000000").status());
    }

    @Test
    void lookup_hitInALaterDatabase_winsEvenIfAnEarlierOneFailed() {
        var client = new StubClient(true, Map.of("obf", found("Listerine", "J&J", List.of("en:mouthwashes"))));

        var result = client.lookup("7891010256043"); // "off" gives no body, "obf" knows it

        assertEquals(LookupStatus.FOUND, result.status());
        assertEquals("Listerine", result.row().productName());
    }

    @Test
    void lookup_budgetAlreadyExpired_asksNoDatabaseAndIsUnavailable() {
        var client = new FailingClient(Map.of("off", new IllegalStateException("must not be called")));

        assertEquals(LookupStatus.UNAVAILABLE, client.lookup("0000000000000", () -> true).status());
    }
}
