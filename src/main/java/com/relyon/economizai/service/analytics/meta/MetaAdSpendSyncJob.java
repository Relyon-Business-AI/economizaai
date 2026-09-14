package com.relyon.economizai.service.analytics.meta;

import com.relyon.economizai.model.MetaAdSpend;
import com.relyon.economizai.repository.MetaAdSpendRepository;
import com.relyon.economizai.service.analytics.meta.MetaAdsClient.MetaAdInsight;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Daily sync of Meta (Facebook/Instagram) ad spend into {@link MetaAdSpend}.
 * INERT until {@link MetaAdsProperties#isConfigured()} — with the default
 * ({@code enabled=false}) it logs {@code meta.sync.skipped} and makes no
 * outbound call, so the feature ships dark until the env vars are set.
 *
 * <p>Follows the repo's transaction discipline: the Graph API fetch runs
 * UNTRANSACTED (never pins a Hikari connection across the HTTP call), and each
 * per-day/campaign upsert lands in its own short {@link TransactionTemplate}
 * block. Every failure is caught and logged — a scheduler must never die — so
 * the next daily run simply retries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaAdSpendSyncJob {

    private final MetaAdsClient metaAdsClient;
    private final MetaAdSpendRepository metaAdSpendRepository;
    private final MetaAdsProperties properties;
    private final TransactionTemplate transactionTemplate;

    /** Once a day, early morning America/Sao_Paulo. */
    @Scheduled(cron = "${meta.ads.cron:0 30 3 * * *}", zone = "${meta.ads.zone:America/Sao_Paulo}")
    public void scheduledSync() {
        syncNow();
    }

    /**
     * Public entry point so the sync can be triggered/tested directly. Returns the
     * number of campaign/day rows upserted (0 when unconfigured or the fetch fails).
     */
    public int syncNow() {
        if (!properties.isConfigured()) {
            log.info("meta.sync.skipped reason=not_configured");
            return 0;
        }

        var until = LocalDate.now();
        var since = until.minusDays(Math.max(1, properties.getSyncDays()));
        List<MetaAdInsight> insights;
        try {
            insights = metaAdsClient.fetchDailyCampaignInsights(since, until);
        } catch (MetaAdsApiException | RestClientException ex) {
            log.warn("meta.sync.failed since={} until={} reason={}", since, until, ex.getMessage());
            return 0;
        }

        var rows = 0;
        for (var insight : insights) {
            try {
                upsert(insight);
                rows++;
            } catch (RuntimeException ex) {
                log.warn("meta.sync.row_failed campaign={} date={} reason={}",
                        insight.campaignId(), insight.spendDate(), ex.getMessage());
            }
        }
        log.info("meta.sync.done days={} rows={}", properties.getSyncDays(), rows);
        return rows;
    }

    /** Upsert one campaign/day row in its own short transaction. */
    private void upsert(MetaAdInsight insight) {
        transactionTemplate.executeWithoutResult(status -> {
            var existing = metaAdSpendRepository
                    .findByCampaignIdAndSpendDate(insight.campaignId(), insight.spendDate())
                    .orElseGet(MetaAdSpend::new);
            existing.setAdAccountId(properties.getAdAccountId());
            existing.setCampaignId(insight.campaignId());
            existing.setCampaignName(insight.campaignName());
            existing.setSpendDate(insight.spendDate());
            existing.setSpend(insight.spend());
            existing.setCurrency(insight.currency());
            existing.setImpressions(insight.impressions());
            existing.setClicks(insight.clicks());
            existing.setReach(insight.reach());
            existing.setSyncedAt(OffsetDateTime.now());
            metaAdSpendRepository.save(existing);
        });
    }
}
