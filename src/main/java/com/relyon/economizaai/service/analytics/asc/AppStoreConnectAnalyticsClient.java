package com.relyon.economizaai.service.analytics.asc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Thin client for the App Store Connect Analytics Reports API.
 *
 * <p>Flow (all calls authenticated with a short-lived ES256 JWT):
 * <ol>
 *   <li>Find or create an ONGOING report request for the app.</li>
 *   <li>List available reports (APP_STORE_ENGAGEMENT and APP_STORE_ACQUISITION, DAILY granularity).</li>
 *   <li>For each report, fetch its segment download URL and parse the gzipped TSV.</li>
 * </ol>
 *
 * <p>Reports that Apple has not yet generated (future or very recent dates) simply
 * don't appear in the list — the sync job ignores them and retries on the next run.
 */
@Slf4j
@Service
public class AppStoreConnectAnalyticsClient {

    private static final String BASE = "https://api.appstoreconnect.apple.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppStoreConnectProperties properties;
    private final AppStoreConnectJwtProvider jwtProvider;

    public AppStoreConnectAnalyticsClient(RestClient.Builder builder,
                                          AppStoreConnectProperties properties,
                                          AppStoreConnectJwtProvider jwtProvider) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restClient = builder.requestFactory(factory).build();
        this.properties = properties;
        this.jwtProvider = jwtProvider;
    }

    /**
     * Returns the daily analytics rows for all dates available in the given range.
     * Each {@link DailyRow} represents one source type on one day.
     * Fetches both engagement (impressions, page views) and acquisition (installs).
     */
    public List<DailyRow> fetchDailyRows(LocalDate from, LocalDate to) {
        var requestId = findOrCreateOngoingRequest();
        var engagementRows = fetchRowsByType(requestId, "APP_STORE_ENGAGEMENT", from, to);
        var acquisitionRows = fetchRowsByType(requestId, "APP_STORE_ACQUISITION", from, to);
        return mergeRows(engagementRows, acquisitionRows);
    }

    // ---- ONGOING request lifecycle -----------------------------------------------

    private String findOrCreateOngoingRequest() {
        var existing = listOngoingRequests();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return createOngoingRequest();
    }

    private List<String> listOngoingRequests() {
        var url = BASE + "/v1/apps/" + properties.getAppId()
                + "/analyticsReportRequests?filter[accessType]=ONGOING&limit=5";
        var json = get(url);
        var ids = new ArrayList<String>();
        var data = json.path("data");
        if (data.isArray()) {
            for (var node : data) {
                ids.add(node.path("id").asText());
            }
        }
        return ids;
    }

    private String createOngoingRequest() {
        var body = """
                {"data":{"type":"analyticsReportRequests","attributes":{"accessType":"ONGOING"},\
                "relationships":{"app":{"data":{"type":"apps","id":"%s"}}}}}"""
                .formatted(properties.getAppId());
        var url = BASE + "/v1/analyticsReportRequests";
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + jwtProvider.bearerToken())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new RuntimeException("ASC create report request failed: " + ex.getMessage(), ex);
        }
        try {
            return objectMapper.readTree(responseBody).path("data").path("id").asText();
        } catch (Exception ex) {
            throw new RuntimeException("ASC create report request parse failed: " + ex.getMessage(), ex);
        }
    }

    // ---- Report listing and download ---------------------------------------------

    private List<DailyRow> fetchRowsByType(String requestId, String reportType, LocalDate from, LocalDate to) {
        var url = BASE + "/v1/analyticsReportRequests/" + requestId + "/reports"
                + "?filter[reportType]=" + reportType
                + "&filter[granularity]=DAILY"
                + "&limit=200";
        var json = get(url);
        var rows = new ArrayList<DailyRow>();
        var data = json.path("data");
        if (!data.isArray()) return rows;

        for (var reportNode : data) {
            var reportId = reportNode.path("id").asText();
            var dateStr = reportNode.path("attributes").path("reportDate").asText("");
            if (dateStr.isBlank()) continue;
            LocalDate reportDate;
            try {
                reportDate = LocalDate.parse(dateStr);
            } catch (Exception ex) {
                continue;
            }
            if (reportDate.isBefore(from) || reportDate.isAfter(to)) continue;

            try {
                var segmentRows = downloadReportSegments(reportId, reportType, reportDate);
                rows.addAll(segmentRows);
            } catch (Exception ex) {
                log.warn("asc.segment.failed reportId={} date={} reason={}", reportId, reportDate, ex.getMessage());
            }
        }
        return rows;
    }

    private List<DailyRow> downloadReportSegments(String reportId, String reportType, LocalDate date) {
        var segUrl = BASE + "/v1/analyticsReports/" + reportId + "/segments?limit=10";
        var segJson = get(segUrl);
        var rows = new ArrayList<DailyRow>();
        var segments = segJson.path("data");
        if (!segments.isArray()) return rows;

        for (var seg : segments) {
            var downloadUrl = seg.path("attributes").path("url").asText("");
            if (downloadUrl.isBlank()) continue;
            try {
                var tsv = downloadGzipTsv(downloadUrl);
                rows.addAll(parseTsv(tsv, reportType, date));
            } catch (Exception ex) {
                log.warn("asc.download.failed date={} reason={}", date, ex.getMessage());
            }
        }
        return rows;
    }

    private String downloadGzipTsv(String url) throws IOException {
        var bytes = restClient.get()
                .uri(URI.create(url))
                .retrieve()
                .body(byte[].class);
        if (bytes == null) return "";
        // Apple may return plain TSV or gzipped TSV depending on the segment
        if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
            try (var gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gz.readAllBytes());
            }
        }
        return new String(bytes);
    }

    // ---- TSV parsing -------------------------------------------------------------

    /**
     * Parses a TSV where each row represents one source type on the given date.
     * Columns are identified by header name (robust to column order changes).
     */
    private List<DailyRow> parseTsv(String tsv, String reportType, LocalDate date) {
        var lines = tsv.strip().split("\n");
        if (lines.length < 2) return List.of();

        var headers = lines[0].strip().split("\t");
        var colIndex = new HashMap<String, Integer>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i].strip().toLowerCase(), i);
        }

        var rows = new ArrayList<DailyRow>();
        for (int i = 1; i < lines.length; i++) {
            var cols = lines[i].split("\t", -1);
            var sourceType = col(cols, colIndex, "source type", "TOTAL");
            long impressions = 0;
            long pageViews = 0;
            long appUnits = 0;

            if ("APP_STORE_ENGAGEMENT".equals(reportType)) {
                impressions = longCol(cols, colIndex, "impressions");
                pageViews = longCol(cols, colIndex, "product page views");
            } else if ("APP_STORE_ACQUISITION".equals(reportType)) {
                appUnits = longCol(cols, colIndex, "app units");
            }

            rows.add(new DailyRow(date, normalize(sourceType), impressions, pageViews, appUnits));
        }
        return rows;
    }

    private static String col(String[] cols, Map<String, Integer> idx, String key, String fallback) {
        var i = idx.get(key);
        if (i == null || i >= cols.length) return fallback;
        var v = cols[i].strip();
        return v.isBlank() ? fallback : v;
    }

    private static long longCol(String[] cols, Map<String, Integer> idx, String key) {
        var i = idx.get(key);
        if (i == null || i >= cols.length) return 0;
        try {
            return Long.parseLong(cols[i].strip().replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Normalizes Apple's display names to stable identifiers. */
    private static String normalize(String sourceType) {
        return switch (sourceType.toLowerCase()) {
            case "app store search" -> "APP_STORE_SEARCH";
            case "app store browse" -> "APP_STORE_BROWSE";
            case "app referrer" -> "APP_REFERRER";
            case "web referrer" -> "WEB_REFERRER";
            case "unavailable" -> "UNAVAILABLE";
            default -> sourceType.toUpperCase().replace(' ', '_');
        };
    }

    // ---- Row merging -------------------------------------------------------------

    /** Merges engagement and acquisition rows on (date, sourceType). */
    private static List<DailyRow> mergeRows(List<DailyRow> engagement, List<DailyRow> acquisition) {
        var byKey = new HashMap<String, DailyRow>();
        for (var row : engagement) {
            byKey.put(row.date() + "|" + row.sourceType(), row);
        }
        for (var row : acquisition) {
            var key = row.date() + "|" + row.sourceType();
            var existing = byKey.get(key);
            if (existing != null) {
                byKey.put(key, new DailyRow(existing.date(), existing.sourceType(),
                        existing.impressions(), existing.pageViews(), row.appUnits()));
            } else {
                byKey.put(key, row);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    // ---- HTTP helper -------------------------------------------------------------

    private JsonNode get(String url) {
        String body;
        try {
            body = restClient.get()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + jwtProvider.bearerToken())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new RuntimeException("ASC GET failed url=" + url + " reason=" + ex.getMessage(), ex);
        }
        if (body == null || body.isBlank()) {
            throw new RuntimeException("ASC GET returned empty body url=" + url);
        }
        try {
            var json = objectMapper.readTree(body);
            var errors = json.get("errors");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                var title = errors.get(0).path("title").asText("unknown");
                throw new RuntimeException("ASC API error: " + title);
            }
            return json;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("ASC GET parse failed: " + ex.getMessage(), ex);
        }
    }

    // ---- Value object ------------------------------------------------------------

    public record DailyRow(LocalDate date, String sourceType,
                           long impressions, long pageViews, long appUnits) {}
}
