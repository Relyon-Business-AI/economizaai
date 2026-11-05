package com.relyon.economizaai.service.analytics.asc;

import com.relyon.economizaai.service.analytics.asc.AppStoreConnectAnalyticsClient.DailyRow;
import com.relyon.economizaai.service.analytics.asc.AppStoreConnectAnalyticsClient.ReportKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppStoreConnectAnalyticsClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    private static final String DOWNLOADS_HEADER = "Date\tApp Name\tApp Apple Identifier\tDownload Type\tApp Version\tDevice"
            + "\tPlatform Version\tSource Type\tPage Type\tPre-Order\tTerritory\tCounts";
    private static final String ENGAGEMENT_HEADER = "Date\tApp Name\tApp Apple Identifier\tEvent\tPage Type\tSource Type"
            + "\tEngagement Type\tDevice\tPlatform Version\tTerritory\tCounts\tUnique Counts";

    private static String download(String date, String type, String source, int count) {
        return String.join("\t", date, "Economiza AI", "6788025621", type, "1.2.3", "iPhone", "iOS 26.5",
                source, "No page", "", "BR", String.valueOf(count));
    }

    private static String engagement(String date, String event, String source, int count) {
        return String.join("\t", date, "Economiza AI", "6788025621", event, "Product page", source, "",
                "iPhone", "iOS 26.5", "BR", String.valueOf(count), String.valueOf(count));
    }

    private static DailyRow find(List<DailyRow> rows, String date, String source) {
        return rows.stream()
                .filter(row -> row.date().toString().equals(date) && row.sourceType().equals(source))
                .findFirst().orElseThrow();
    }

    @Test
    void datesRepeatedAcrossInstancesAreCountedOnceFromTheLatestInstance() {
        var olderInstance = String.join("\n", DOWNLOADS_HEADER,
                download("2026-10-01", "First-time download", "App Store search", 1),
                download("2026-10-02", "First-time download", "App Store search", 1));
        var newerInstance = String.join("\n", DOWNLOADS_HEADER,
                download("2026-10-02", "First-time download", "App Store search", 2),
                download("2026-10-03", "First-time download", "Web referrer", 1));

        var rows = AppStoreConnectAnalyticsClient.parseInstances(
                List.of(olderInstance, newerInstance), ReportKind.DOWNLOADS, FROM, TO);

        assertThat(find(rows, "2026-10-01", "APP_STORE_SEARCH").appUnits()).isEqualTo(1);
        assertThat(find(rows, "2026-10-02", "APP_STORE_SEARCH").appUnits()).isEqualTo(2);
        assertThat(find(rows, "2026-10-03", "WEB_REFERRER").appUnits()).isEqualTo(1);
        assertThat(rows.stream().mapToLong(DailyRow::appUnits).sum()).isEqualTo(4);
    }

    @Test
    void onlyFirstTimeDownloadsCountAsAcquisition() {
        var tsv = String.join("\n", DOWNLOADS_HEADER,
                download("2026-10-05", "First-time download", "App referrer", 3),
                download("2026-10-05", "Redownload", "App referrer", 5),
                download("2026-10-05", "Auto-update", "App referrer", 9));

        var rows = AppStoreConnectAnalyticsClient.parseInstances(List.of(tsv), ReportKind.DOWNLOADS, FROM, TO);

        assertThat(find(rows, "2026-10-05", "APP_REFERRER").appUnits()).isEqualTo(3);
    }

    @Test
    void engagementSplitsImpressionsAndPageViewsAndIgnoresOutOfWindowDates() {
        var tsv = String.join("\n", ENGAGEMENT_HEADER,
                engagement("2026-10-07", "Impression", "App Store search", 10),
                engagement("2026-10-07", "Impression", "App Store search", 5),
                engagement("2026-10-07", "Page view", "App Store search", 4),
                engagement("2026-10-07", "Tap", "App Store search", 99),
                engagement("2026-09-30", "Impression", "App Store search", 50));

        var rows = AppStoreConnectAnalyticsClient.parseInstances(List.of(tsv), ReportKind.ENGAGEMENT, FROM, TO);

        var day = find(rows, "2026-10-07", "APP_STORE_SEARCH");
        assertThat(day.impressions()).isEqualTo(15);
        assertThat(day.pageViews()).isEqualTo(4);
        assertThat(rows).noneMatch(row -> row.date().isBefore(FROM));
    }
}
