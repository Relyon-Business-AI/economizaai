package com.relyon.economizaai.service.analytics.asc;

import com.relyon.economizaai.dto.response.StoreAnalyticsResponse;
import com.relyon.economizaai.model.StoreAnalyticsDaily;
import com.relyon.economizaai.repository.StoreAnalyticsDailyRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AppStoreAnalyticsService {

    private static final String PLATFORM = "IOS";
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "APP_STORE_SEARCH", "Busca no App Store",
            "APP_STORE_BROWSE", "Navegação no App Store",
            "APP_REFERRER", "Referência de app",
            "WEB_REFERRER", "Referência web",
            "UNAVAILABLE", "Indisponível",
            "TOTAL", "Total"
    );
    private static final String DATA_DELAY_NOTE = "dados das lojas têm atraso de 24-72h";

    private final StoreAnalyticsDailyRepository repository;
    private final AppStoreConnectProperties properties;

    public StoreAnalyticsResponse report(int days) {
        if (!properties.isConfigured()) {
            return StoreAnalyticsResponse.unconfigured();
        }

        var to = BrazilClock.today().minusDays(1); // yesterday (Apple delay)
        var from = to.minusDays(Math.max(1, days) - 1L);

        var rows = repository.findByPlatformAndReportDateBetweenOrderByReportDateDesc(PLATFORM, from, to);
        var syncedAt = rows.stream()
                .map(StoreAnalyticsDaily::getSyncedAt)
                .max(OffsetDateTime::compareTo)
                .orElse(null);

        var bySource = aggregateBySource(rows);
        var daily = aggregateByDate(rows);

        long totalImpressions = bySource.stream().mapToLong(StoreAnalyticsResponse.SourceRow::impressions).sum();
        long totalPageViews = bySource.stream().mapToLong(StoreAnalyticsResponse.SourceRow::pageViews).sum();
        long totalInstalls = bySource.stream().mapToLong(StoreAnalyticsResponse.SourceRow::installs).sum();

        return new StoreAnalyticsResponse(
                true,
                DATA_DELAY_NOTE,
                syncedAt,
                from, to,
                new StoreAnalyticsResponse.Totals(totalImpressions, totalPageViews, totalInstalls),
                bySource,
                daily
        );
    }

    private List<StoreAnalyticsResponse.SourceRow> aggregateBySource(List<StoreAnalyticsDaily> rows) {
        var map = new HashMap<String, long[]>(); // sourceType → [impressions, pageViews, appUnits]
        for (var row : rows) {
            var acc = map.computeIfAbsent(row.getSourceType(), key -> new long[3]);
            acc[0] += row.getImpressions();
            acc[1] += row.getPageViews();
            acc[2] += row.getAppUnits();
        }
        return map.entrySet().stream()
                .sorted((entry1, entry2) -> Long.compare(entry2.getValue()[2], entry1.getValue()[2]))
                .map(entry -> new StoreAnalyticsResponse.SourceRow(
                        entry.getKey(),
                        SOURCE_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[2]))
                .toList();
    }

    private List<StoreAnalyticsResponse.DailyRow> aggregateByDate(List<StoreAnalyticsDaily> rows) {
        var map = new HashMap<String, long[]>(); // date → [impressions, pageViews, appUnits]
        for (var row : rows) {
            var key = row.getReportDate().toString();
            var acc = map.computeIfAbsent(key, k -> new long[3]);
            acc[0] += row.getImpressions();
            acc[1] += row.getPageViews();
            acc[2] += row.getAppUnits();
        }
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new StoreAnalyticsResponse.DailyRow(
                        java.time.LocalDate.parse(entry.getKey()),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[2]))
                .toList();
    }
}
