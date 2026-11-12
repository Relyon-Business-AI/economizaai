package com.relyon.economizaai.service.analytics.gsc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.relyon.economizaai.dto.response.GscReportResponse;
import com.relyon.economizaai.time.BrazilClock;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin wrapper over the Google Search Console Search Analytics API.
 * Inert until {@link GoogleSearchConsoleProperties#isConfigured()} —
 * returns a {@code configured=false} response with a setup hint.
 *
 * <p>Authentication uses a Bearer access token stored in the env var
 * GOOGLE_SC_ACCESS_TOKEN. Google access tokens expire after ~1 hour;
 * rotate via Google's OAuth2 Playground or a service-account flow.
 *
 * <p>A report costs three Google round-trips, so it is built with the three queries in
 * parallel over a reused connection and then cached per window for
 * {@code google.search-console.cache-ttl-minutes} — Search Console data itself lags by days,
 * so a few hours of staleness changes nothing. Failed reports are never cached.
 */
@Slf4j
@Service
public class GoogleSearchConsoleService {

    private static final String BASE_URL = "https://searchconsole.googleapis.com";

    private static final int MAX_CACHED_WINDOWS = 64;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GoogleSearchConsoleProperties properties;
    private final GoogleServiceAccountTokenProvider tokenProvider;
    private final Cache<ReportKey, GscReportResponse> reportCache;
    private final ExecutorService queryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** One cached report: the window length and the day it ends on (so a new day is a new entry). */
    private record ReportKey(int windowDays, LocalDate to) {
    }

    public GoogleSearchConsoleService(RestClient.Builder builder, GoogleSearchConsoleProperties properties,
                                      GoogleServiceAccountTokenProvider tokenProvider) {
        // The JDK client keeps the connection to Google alive between calls; the simple factory
        // opened a new TLS connection for each of the three queries.
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = builder.requestFactory(factory).build();
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.reportCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, properties.getCacheTtlMinutes())))
                .maximumSize(MAX_CACHED_WINDOWS)
                .build();
    }

    @PreDestroy
    void shutdownQueryExecutor() {
        queryExecutor.shutdown();
    }

    /** Service-account token (self-renewing) when configured, else the legacy static token. */
    private String bearerToken() {
        return properties.hasServiceAccount() ? tokenProvider.accessToken() : properties.getAccessToken();
    }

    public GscReportResponse report(int days) {
        var windowDays = Math.max(1, days);
        var to = BrazilClock.today();
        var from = to.minusDays(windowDays - 1L);

        if (!properties.isConfigured()) {
            return new GscReportResponse(false, from, to, 0, 0, 0, 0, List.of(), List.of(),
                    "Configure GOOGLE_SC_ENABLED=true e GOOGLE_SC_ACCESS_TOKEN para ver dados do Search Console.");
        }

        var key = new ReportKey(windowDays, to);
        var cached = reportCache.getIfPresent(key);
        if (cached != null) {
            log.debug("gsc.report.cache_hit days={}", windowDays);
            return cached;
        }

        var startedAt = System.nanoTime();
        try {
            var report = fetchReport(windowDays, from, to);
            reportCache.put(key, report);
            log.info("gsc.report days={} clicks={} impressions={} queries={} ms={}", windowDays, report.totalClicks(),
                    report.totalImpressions(), report.topQueries().size(), elapsedMillis(startedAt));
            return report;
        } catch (Exception ex) {
            log.warn("gsc.report.error days={} ms={} error={}", windowDays, elapsedMillis(startedAt), ex.getMessage());
            return new GscReportResponse(true, from, to, 0, 0, 0, 0, List.of(), List.of(),
                    "Erro ao buscar dados do Search Console: " + ex.getMessage());
        }
    }

    /** The three Search Analytics queries run at once: the report takes as long as the slowest, not the sum. */
    private GscReportResponse fetchReport(int windowDays, LocalDate from, LocalDate to) {
        var token = bearerToken();
        var summaryQuery = CompletableFuture.supplyAsync(() -> post(token, summaryBody(from, to)), queryExecutor);
        var timelineQuery = CompletableFuture.supplyAsync(() -> post(token, timelineBody(from, to, windowDays)), queryExecutor);
        var topQueriesQuery = CompletableFuture.supplyAsync(() -> post(token, topQueriesBody(from, to)), queryExecutor);

        JsonNode summary;
        JsonNode timeline;
        JsonNode topQueries;
        try {
            summary = summaryQuery.join();
            timeline = timelineQuery.join();
            topQueries = topQueriesQuery.join();
        } catch (CompletionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : ex;
        }

        var totalClicks = 0L;
        var totalImpressions = 0L;
        var avgCtr = 0d;
        var avgPosition = 0d;
        var summaryRows = summary.get("rows");
        if (summaryRows != null && summaryRows.isArray() && summaryRows.size() > 0) {
            var row = summaryRows.get(0);
            totalClicks = (long) row.path("clicks").asDouble(0);
            totalImpressions = (long) row.path("impressions").asDouble(0);
            avgCtr = row.path("ctr").asDouble(0);
            avgPosition = row.path("position").asDouble(0);
        }
        return new GscReportResponse(true, from, to, totalClicks, totalImpressions, avgCtr, avgPosition,
                parseDailyRows(timeline), parseQueryRows(topQueries), null);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private static String summaryBody(LocalDate from, LocalDate to) {
        return String.format("{\"startDate\":\"%s\",\"endDate\":\"%s\",\"rowLimit\":1}", from, to);
    }

    /** One row per day of the window (the old fixed limit of 90 cut the oldest days of longer windows). */
    private static String timelineBody(LocalDate from, LocalDate to, int windowDays) {
        return String.format(
                "{\"startDate\":\"%s\",\"endDate\":\"%s\",\"dimensions\":[\"date\"],\"rowLimit\":%d}", from, to, windowDays);
    }

    private static String topQueriesBody(LocalDate from, LocalDate to) {
        return String.format(
                "{\"startDate\":\"%s\",\"endDate\":\"%s\",\"dimensions\":[\"query\"],\"rowLimit\":10}", from, to);
    }

    /** Package-private so tests can stub the network. */
    JsonNode post(String bearerToken, String jsonBody) {
        var encodedSite = URLEncoder.encode(properties.getSiteUrl(), StandardCharsets.UTF_8);
        var url = BASE_URL + "/webmasters/" + properties.getApiVersion()
                + "/sites/" + encodedSite + "/searchAnalytics/query";
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new RuntimeException("GSC API request failed: " + ex.getMessage(), ex);
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("GSC API returned empty body");
        }
        try {
            var json = objectMapper.readTree(responseBody);
            var error = json.get("error");
            if (error != null && !error.isNull()) {
                throw new RuntimeException("GSC API error: " + error.path("message").asText("unknown"));
            }
            return json;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("GSC API parse error: " + ex.getMessage(), ex);
        }
    }

    private List<GscReportResponse.DailyRow> parseDailyRows(JsonNode json) {
        var rows = new ArrayList<GscReportResponse.DailyRow>();
        var data = json.get("rows");
        if (data == null || !data.isArray()) return rows;
        for (var node : data) {
            var dateKey = node.path("keys").get(0);
            if (dateKey == null || dateKey.isMissingNode()) continue;
            rows.add(new GscReportResponse.DailyRow(
                    LocalDate.parse(dateKey.asText()),
                    (long) node.path("clicks").asDouble(0),
                    (long) node.path("impressions").asDouble(0),
                    node.path("ctr").asDouble(0),
                    node.path("position").asDouble(0)));
        }
        return rows;
    }

    private List<GscReportResponse.QueryRow> parseQueryRows(JsonNode json) {
        var rows = new ArrayList<GscReportResponse.QueryRow>();
        var data = json.get("rows");
        if (data == null || !data.isArray()) return rows;
        for (var node : data) {
            var queryKey = node.path("keys").get(0);
            if (queryKey == null || queryKey.isMissingNode()) continue;
            rows.add(new GscReportResponse.QueryRow(
                    queryKey.asText(),
                    (long) node.path("clicks").asDouble(0),
                    (long) node.path("impressions").asDouble(0),
                    node.path("ctr").asDouble(0),
                    node.path("position").asDouble(0)));
        }
        return rows;
    }
}
