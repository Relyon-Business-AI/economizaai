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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Thin client for the App Store Connect Analytics Reports API.
 *
 * <p>Flow (all calls authenticated with a short-lived ES256 JWT):
 * <ol>
 *   <li>Find or create an ONGOING report request for the app.</li>
 *   <li>Find the two reports by category + name ("App Store Discovery and Engagement
 *       Standard", "App Downloads Standard").</li>
 *   <li>List each report's DAILY instances, download every instance's segments (gzipped TSV).</li>
 *   <li>Parse, deduping dates that repeat across instances (latest instance wins).</li>
 * </ol>
 *
 * <p>Apple keeps ~1 month of DAILY instances and publishes with 24-72h delay; missing
 * dates are simply absent and get filled on a later run.
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

    /** The two Apple reports we read: category filter + exact report name. */
    enum ReportKind {
        ENGAGEMENT("APP_STORE_ENGAGEMENT", "App Store Discovery and Engagement Standard"),
        DOWNLOADS("COMMERCE", "App Downloads Standard");

        private final String category;
        private final String reportName;

        ReportKind(String category, String reportName) {
            this.category = category;
            this.reportName = reportName;
        }
    }

    /**
     * Returns the daily analytics rows for all dates available in the given range.
     * Each {@link DailyRow} represents one source type on one day.
     * Fetches both engagement (impressions, page views) and downloads (first-time installs).
     */
    public List<DailyRow> fetchDailyRows(LocalDate from, LocalDate to) {
        var requestId = findOrCreateOngoingRequest();
        var engagementRows = fetchReport(requestId, ReportKind.ENGAGEMENT, from, to);
        var downloadRows = fetchReport(requestId, ReportKind.DOWNLOADS, from, to);
        return mergeRows(engagementRows, downloadRows);
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

    /**
     * Report → its DAILY instances → each instance's segments (gzipped TSV). An instance
     * carries rows for SEVERAL data dates (Apple re-publishes the last 2-3 days), so dates
     * repeat across instances: the latest instance (by processingDate) wins each date
     * wholesale — summing them would double/triple count.
     */
    private List<DailyRow> fetchReport(String requestId, ReportKind kind, LocalDate from, LocalDate to) {
        var reportId = findReportId(requestId, kind);
        if (reportId == null) {
            log.warn("asc.report.missing category={} name={}", kind.category, kind.reportName);
            return List.of();
        }
        var instances = listDailyInstances(reportId);
        var tsvByInstance = new ArrayList<String>();
        for (var instance : instances) {
            if (instance.processingDate().isBefore(from)) continue;
            tsvByInstance.add(downloadInstance(instance));
        }
        var rows = parseInstances(tsvByInstance, kind, from, to);
        log.info("asc.report.fetched kind={} instances={} rows={}", kind, tsvByInstance.size(), rows.size());
        return rows;
    }

    private String findReportId(String requestId, ReportKind kind) {
        var url = BASE + "/v1/analyticsReportRequests/" + requestId + "/reports"
                + "?filter[category]=" + kind.category + "&limit=200";
        for (var reportNode : get(url).path("data")) {
            if (kind.reportName.equals(reportNode.path("attributes").path("name").asText())) {
                return reportNode.path("id").asText();
            }
        }
        return null;
    }

    private record ReportInstance(String id, LocalDate processingDate) {}

    /** All DAILY instances, oldest processingDate first (follows {@code links.next}). */
    private List<ReportInstance> listDailyInstances(String reportId) {
        var instances = new ArrayList<ReportInstance>();
        var url = BASE + "/v1/analyticsReports/" + reportId + "/instances?filter[granularity]=DAILY&limit=200";
        while (url != null && !url.isBlank()) {
            var json = get(url);
            for (var node : json.path("data")) {
                var processingDate = node.path("attributes").path("processingDate").asText("");
                if (!processingDate.isBlank()) {
                    instances.add(new ReportInstance(node.path("id").asText(), LocalDate.parse(processingDate)));
                }
            }
            url = json.path("links").path("next").asText(null);
        }
        instances.sort(Comparator.comparing(ReportInstance::processingDate));
        return instances;
    }

    /** Concatenates every segment of one instance (header kept once). */
    private String downloadInstance(ReportInstance instance) {
        var segmentsUrl = BASE + "/v1/analyticsReportInstances/" + instance.id() + "/segments?limit=50";
        var combined = new StringBuilder();
        for (var segment : get(segmentsUrl).path("data")) {
            var downloadUrl = segment.path("attributes").path("url").asText("");
            if (downloadUrl.isBlank()) continue;
            try {
                var tsv = downloadGzipTsv(downloadUrl).strip();
                var headerEnd = tsv.indexOf('\n');
                if (combined.isEmpty()) {
                    combined.append(tsv).append('\n');
                } else if (headerEnd >= 0) {
                    combined.append(tsv.substring(headerEnd + 1)).append('\n');
                }
            } catch (IOException | RestClientException ex) {
                log.warn("asc.download.failed instance={} date={} reason={}",
                        instance.id(), instance.processingDate(), ex.getMessage());
            }
        }
        return combined.toString();
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
     * Turns the TSVs of a report's instances (oldest first) into one row per (date, source).
     * Each data date is taken only from the LATEST instance that carries it. Engagement rows
     * count {@code Event=Impression|Page view}; download rows count
     * {@code Download Type=First-time download} (redownloads/updates/restores are not acquisition).
     */
    static List<DailyRow> parseInstances(List<String> tsvByInstance, ReportKind kind, LocalDate from, LocalDate to) {
        var latestRowsByDate = new HashMap<LocalDate, DateRows>();
        for (var tsv : tsvByInstance) {
            var lines = tsv.strip().split("\r?\n");
            if (lines.length < 2) continue;
            var columns = columnIndex(lines[0]);
            var dateColumn = columns.get("date");
            if (dateColumn == null) continue;
            var rowsByDate = new HashMap<LocalDate, DateRows>();
            for (var lineIndex = 1; lineIndex < lines.length; lineIndex++) {
                var cells = lines[lineIndex].split("\t", -1);
                parseDate(cell(cells, dateColumn))
                        .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                        .ifPresent(date -> rowsByDate.computeIfAbsent(date, ignored -> new DateRows(columns, new ArrayList<>()))
                                .rows().add(cells));
            }
            latestRowsByDate.putAll(rowsByDate);
        }

        var totals = new HashMap<String, long[]>(); // "date|source" → [impressions, pageViews, firstDownloads]
        latestRowsByDate.forEach((date, dateRows) -> dateRows.rows()
                .forEach(cells -> accumulate(totals, date, cells, dateRows.columns(), kind)));
        return totals.entrySet().stream()
                .map(entry -> {
                    var keyParts = entry.getKey().split("\\|", 2);
                    var counts = entry.getValue();
                    return new DailyRow(LocalDate.parse(keyParts[0]), keyParts[1], counts[0], counts[1], counts[2]);
                })
                .toList();
    }

    /** One data date's rows plus the column layout of the instance they came from. */
    private record DateRows(Map<String, Integer> columns, List<String[]> rows) {}

    private static void accumulate(Map<String, long[]> totals, LocalDate date, String[] cells,
                                   Map<String, Integer> columns, ReportKind kind) {
        var count = parseCount(cell(cells, columns.get("counts")));
        var source = normalize(Optional.ofNullable(cell(cells, columns.get("source type")))
                .filter(value -> !value.isBlank()).orElse("Unavailable"));
        var counts = totals.computeIfAbsent(date + "|" + source, ignored -> new long[3]);
        if (kind == ReportKind.ENGAGEMENT) {
            var event = cell(cells, columns.get("event"));
            if ("Impression".equalsIgnoreCase(event)) counts[0] += count;
            else if ("Page view".equalsIgnoreCase(event)) counts[1] += count;
        } else if ("First-time download".equalsIgnoreCase(cell(cells, columns.get("download type")))) {
            counts[2] += count;
        }
    }

    private static Map<String, Integer> columnIndex(String headerLine) {
        var headers = headerLine.split("\t");
        var index = new HashMap<String, Integer>();
        for (var position = 0; position < headers.length; position++) {
            index.put(headers[position].strip().toLowerCase(), position);
        }
        return index;
    }

    private static String cell(String[] cells, Integer column) {
        return column == null || column >= cells.length ? null : cells[column].strip();
    }

    private static Optional<LocalDate> parseDate(String value) {
        try {
            return Optional.ofNullable(value).map(LocalDate::parse);
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    private static long parseCount(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value.replace(",", ""));
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
