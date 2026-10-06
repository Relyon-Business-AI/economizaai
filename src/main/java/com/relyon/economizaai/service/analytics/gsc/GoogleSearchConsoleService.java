package com.relyon.economizaai.service.analytics.gsc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.dto.response.GscReportResponse;
import com.relyon.economizaai.time.BrazilClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over the Google Search Console Search Analytics API.
 * Inert until {@link GoogleSearchConsoleProperties#isConfigured()} —
 * returns a {@code configured=false} response with a setup hint.
 *
 * <p>Authentication uses a Bearer access token stored in the env var
 * GOOGLE_SC_ACCESS_TOKEN. Google access tokens expire after ~1 hour;
 * rotate via Google's OAuth2 Playground or a service-account flow.
 */
@Slf4j
@Service
public class GoogleSearchConsoleService {

    private static final String BASE_URL = "https://searchconsole.googleapis.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GoogleSearchConsoleProperties properties;

    public GoogleSearchConsoleService(RestClient.Builder builder, GoogleSearchConsoleProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restClient = builder.requestFactory(factory).build();
        this.properties = properties;
    }

    public GscReportResponse report(int days) {
        var windowDays = Math.max(1, days);
        var to = BrazilClock.today();
        var from = to.minusDays(windowDays - 1L);

        if (!properties.isConfigured()) {
            return new GscReportResponse(false, from, to, 0, 0, 0, 0, List.of(), List.of(),
                    "Configure GOOGLE_SC_ENABLED=true e GOOGLE_SC_ACCESS_TOKEN para ver dados do Search Console.");
        }

        try {
            var summary = querySummary(from, to);
            var timeline = queryTimeline(from, to);
            var topQueries = queryTopQueries(from, to);

            long totalClicks = 0;
            long totalImpressions = 0;
            double avgCtr = 0;
            double avgPosition = 0;

            var summaryRows = summary.get("rows");
            if (summaryRows != null && summaryRows.isArray() && summaryRows.size() > 0) {
                var row = summaryRows.get(0);
                totalClicks = (long) row.path("clicks").asDouble(0);
                totalImpressions = (long) row.path("impressions").asDouble(0);
                avgCtr = row.path("ctr").asDouble(0);
                avgPosition = row.path("position").asDouble(0);
            }

            var dailyRows = parseDailyRows(timeline);
            var queryRows = parseQueryRows(topQueries);

            log.info("gsc.report days={} clicks={} impressions={} queries={}", windowDays, totalClicks, totalImpressions, queryRows.size());
            return new GscReportResponse(true, from, to, totalClicks, totalImpressions, avgCtr, avgPosition, dailyRows, queryRows, null);
        } catch (Exception ex) {
            log.warn("gsc.report.error days={} error={}", windowDays, ex.getMessage());
            return new GscReportResponse(true, from, to, 0, 0, 0, 0, List.of(), List.of(),
                    "Erro ao buscar dados do Search Console: " + ex.getMessage());
        }
    }

    private JsonNode querySummary(LocalDate from, LocalDate to) {
        var body = String.format("{\"startDate\":\"%s\",\"endDate\":\"%s\",\"rowLimit\":1}", from, to);
        return post(body);
    }

    private JsonNode queryTimeline(LocalDate from, LocalDate to) {
        var body = String.format(
                "{\"startDate\":\"%s\",\"endDate\":\"%s\",\"dimensions\":[\"date\"],\"rowLimit\":90}", from, to);
        return post(body);
    }

    private JsonNode queryTopQueries(LocalDate from, LocalDate to) {
        var body = String.format(
                "{\"startDate\":\"%s\",\"endDate\":\"%s\",\"dimensions\":[\"query\"],\"rowLimit\":10}", from, to);
        return post(body);
    }

    private JsonNode post(String jsonBody) {
        var encodedSite = URLEncoder.encode(properties.getSiteUrl(), StandardCharsets.UTF_8);
        var url = BASE_URL + "/webmasters/" + properties.getApiVersion()
                + "/sites/" + encodedSite + "/searchAnalytics/query";
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + properties.getAccessToken())
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
