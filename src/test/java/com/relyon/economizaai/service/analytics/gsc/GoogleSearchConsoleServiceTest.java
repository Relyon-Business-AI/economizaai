package com.relyon.economizaai.service.analytics.gsc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleSearchConsoleServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GoogleSearchConsoleProperties configuredProperties() {
        var properties = new GoogleSearchConsoleProperties();
        properties.setEnabled(true);
        properties.setAccessToken("static-token");
        return properties;
    }

    /** The service with the network stubbed out: records every request body it would have sent. */
    private static class StubbedService extends GoogleSearchConsoleService {
        final List<String> requestBodies = new CopyOnWriteArrayList<>();
        volatile boolean failing;
        volatile CountDownLatch allThreeInFlight;

        StubbedService(GoogleSearchConsoleProperties properties) {
            super(RestClient.builder(), properties, new GoogleServiceAccountTokenProvider(properties, RestClient.builder()));
        }

        @Override
        JsonNode post(String bearerToken, String jsonBody) {
            requestBodies.add(jsonBody);
            if (allThreeInFlight != null) {
                allThreeInFlight.countDown();
                awaitTheOtherQueries();
            }
            if (failing) throw new IllegalStateException("GSC API request failed: boom");
            return readJson(responseFor(jsonBody));
        }

        private void awaitTheOtherQueries() {
            try {
                if (!allThreeInFlight.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the three queries did not run in parallel");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        private static String responseFor(String jsonBody) {
            if (jsonBody.contains("\"date\"")) {
                return "{\"rows\":[{\"keys\":[\"2026-11-10\"],\"clicks\":3,\"impressions\":40,\"ctr\":0.075,\"position\":8.2}]}";
            }
            if (jsonBody.contains("\"query\"")) {
                return "{\"rows\":[{\"keys\":[\"economiza ai\"],\"clicks\":9,\"impressions\":50,\"ctr\":0.18,\"position\":1.4}]}";
            }
            return "{\"rows\":[{\"clicks\":24,\"impressions\":382,\"ctr\":0.0628,\"position\":7.5}]}";
        }

        private static JsonNode readJson(String json) {
            try {
                return JSON.readTree(json);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Test
    void buildsTheReportFromTheThreeQueries() {
        var service = new StubbedService(configuredProperties());

        var report = service.report(30);

        assertTrue(report.configured());
        assertEquals(24, report.totalClicks());
        assertEquals(382, report.totalImpressions());
        assertEquals(1, report.timeline().size());
        assertEquals("economiza ai", report.topQueries().get(0).query());
        assertNull(report.note());
        assertEquals(3, service.requestBodies.size());
    }

    @Test
    void reusesTheCachedReportForTheSameWindow() {
        var service = new StubbedService(configuredProperties());

        var first = service.report(30);
        var second = service.report(30);

        assertEquals(first, second);
        assertEquals(3, service.requestBodies.size(), "the second call must not reach Google");
    }

    @Test
    void aDifferentWindowIsFetchedSeparately() {
        var service = new StubbedService(configuredProperties());

        service.report(7);
        service.report(30);

        assertEquals(6, service.requestBodies.size());
    }

    @Test
    void aFailedReportIsNotCached() {
        var service = new StubbedService(configuredProperties());
        service.failing = true;

        var failed = service.report(30);
        assertTrue(failed.configured());
        assertNotNull(failed.note());
        assertEquals(0, failed.totalClicks());

        service.failing = false;
        var recovered = service.report(30);

        assertNull(recovered.note());
        assertEquals(24, recovered.totalClicks());
    }

    @Test
    void theThreeQueriesRunInParallel() {
        var service = new StubbedService(configuredProperties());
        // Each stubbed query blocks until all three have started: a sequential run would time out.
        service.allThreeInFlight = new CountDownLatch(3);

        var report = service.report(30);

        assertNull(report.note());
        assertEquals(24, report.totalClicks());
    }

    @Test
    void theTimelineAsksForOneRowPerDayOfTheWindow() {
        var service = new StubbedService(configuredProperties());

        service.report(200);

        var timelineBody = service.requestBodies.stream().filter(body -> body.contains("\"date\"")).findFirst().orElseThrow();
        assertTrue(timelineBody.contains("\"rowLimit\":200"));
    }

    @Test
    void anUnconfiguredIntegrationNeverCallsGoogle() {
        var service = new StubbedService(new GoogleSearchConsoleProperties());

        var report = service.report(30);

        assertFalse(report.configured());
        assertTrue(service.requestBodies.isEmpty());
    }
}
