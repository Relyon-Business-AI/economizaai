package com.relyon.economizaai.service.analytics.asc;

import com.relyon.economizaai.model.StoreAnalyticsDaily;
import com.relyon.economizaai.repository.StoreAnalyticsDailyRepository;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

/**
 * Daily sync of App Store Connect analytics into {@link StoreAnalyticsDaily}.
 * Inert until {@link AppStoreConnectProperties#isConfigured()} — logs
 * {@code asc.sync.skipped} and makes no outbound call otherwise.
 *
 * <p>Fetches up to {@code apple.asc.sync-days} days of history, upserts each
 * (platform, date, sourceType) row. Since Apple reports have a 24-72h delay,
 * today and yesterday may not yet be available — the client simply skips
 * missing dates and they are backfilled on the next run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppStoreAnalyticsSyncJob {

    private static final String PLATFORM = "IOS";

    private final AppStoreConnectAnalyticsClient client;
    private final StoreAnalyticsDailyRepository repository;
    private final AppStoreConnectProperties properties;
    private final TransactionTemplate transactionTemplate;

    /** Once a day, early morning Brasília time (after Apple has published the previous day). */
    @Scheduled(cron = "${apple.asc.cron:0 0 5 * * *}", zone = "America/Sao_Paulo")
    public void scheduledSync() {
        syncNow();
    }

    /**
     * Triggers a sync immediately. Returns the number of rows upserted (0 when unconfigured).
     * Safe to call manually from the admin endpoint.
     */
    public int syncNow() {
        if (!properties.isConfigured()) {
            log.info("asc.sync.skipped reason=not_configured");
            return 0;
        }

        var to = BrazilClock.today().minusDays(1); // yesterday (today not yet available)
        var from = to.minusDays(Math.max(1, properties.getSyncDays()) - 1L);

        log.info("asc.sync.start from={} to={}", from, to);
        try {
            var rows = client.fetchDailyRows(from, to);
            if (rows.isEmpty()) {
                log.info("asc.sync.done rows=0 (no data available for range)");
                return 0;
            }
            var upserted = 0;
            for (var row : rows) {
                try {
                    upsert(row);
                    upserted++;
                } catch (RuntimeException ex) {
                    log.warn("asc.sync.row_failed date={} source={} reason={}",
                            row.date(), row.sourceType(), ex.getMessage());
                }
            }
            log.info("asc.sync.done rows={} upserted={}", rows.size(), upserted);
            return upserted;
        } catch (RuntimeException ex) {
            log.warn("asc.sync.failed reason={}", ex.getMessage());
            return 0;
        }
    }

    private void upsert(AppStoreConnectAnalyticsClient.DailyRow row) {
        transactionTemplate.executeWithoutResult(status -> {
            var existing = repository.findByPlatformAndReportDateAndSourceType(
                    PLATFORM, row.date(), row.sourceType()).orElseGet(StoreAnalyticsDaily::new);
            existing.setPlatform(PLATFORM);
            existing.setReportDate(row.date());
            existing.setSourceType(row.sourceType());
            // Merge: keep max of stored vs new (engagement and acquisition come in separate passes)
            existing.setImpressions(Math.max(existing.getImpressions(), row.impressions()));
            existing.setPageViews(Math.max(existing.getPageViews(), row.pageViews()));
            existing.setAppUnits(Math.max(existing.getAppUnits(), row.appUnits()));
            existing.setSyncedAt(OffsetDateTime.now());
            repository.save(existing);
        });
    }
}
