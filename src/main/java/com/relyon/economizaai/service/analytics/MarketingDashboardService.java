package com.relyon.economizaai.service.analytics;

import com.relyon.economizaai.dto.response.GscReportResponse;
import com.relyon.economizaai.dto.response.MarketingDashboardResponse;
import com.relyon.economizaai.dto.response.MarketingDashboardResponse.CampaignRow;
import com.relyon.economizaai.dto.response.MarketingDashboardResponse.MetaSection;
import com.relyon.economizaai.model.MetaCampaign;
import com.relyon.economizaai.repository.MetaAdSpendRepository;
import com.relyon.economizaai.repository.MetaCampaignRepository;
import com.relyon.economizaai.service.analytics.gsc.GoogleSearchConsoleService;
import com.relyon.economizaai.service.analytics.meta.MetaAdsProperties;
import com.relyon.economizaai.time.BrazilClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingDashboardService {

    private static final String CURRENCY = "BRL";

    private final MetaAdSpendRepository metaAdSpendRepository;
    private final MetaCampaignRepository metaCampaignRepository;
    private final MetaAdsProperties metaAdsProperties;
    private final GoogleSearchConsoleService googleSearchConsoleService;

    @Transactional(readOnly = true)
    public MarketingDashboardResponse dashboard(int days) {
        var windowDays = Math.max(1, days);
        var to = BrazilClock.today();
        var from = to.minusDays(windowDays - 1L);

        var metaSection = buildMetaSection(from, to);
        var organicSection = googleSearchConsoleService.report(windowDays);

        log.info("marketing.dashboard days={} metaConfigured={} organicConfigured={} metaSpend={} organicImpressions={}",
                windowDays, metaSection.configured(), organicSection.configured(),
                metaSection.totalSpend(), organicSection.totalImpressions());

        return new MarketingDashboardResponse(windowDays, from, to, metaSection, organicSection);
    }

    private MetaSection buildMetaSection(LocalDate from, LocalDate to) {
        if (!metaAdsProperties.isConfigured()) {
            return new MetaSection(false, CURRENCY, BigDecimal.ZERO, 0L, 0L, 0L,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Collections.emptyList());
        }

        var campaignsById = metaCampaignRepository.findAll().stream()
                .collect(Collectors.toMap(MetaCampaign::getCampaignId, campaign -> campaign, (first, second) -> first));

        var campaignRows = new ArrayList<CampaignRow>();
        long totalImpressions = 0L;
        long totalClicks = 0L;
        long totalReach = 0L;
        var totalSpend = BigDecimal.ZERO;
        var totalBudgetRemaining = BigDecimal.ZERO;

        for (var row : metaAdSpendRepository.campaignTotalsBetween(from, to)) {
            var campaignId = (String) row[0];
            var campaignName = (String) row[1];
            var spend = scale((BigDecimal) row[2]);
            var clicks = toLong(row[3]);
            var impressions = toLong(row[4]);
            var reach = toLong(row[5]);
            var meta = campaignsById.get(campaignId);
            var status = meta == null ? null : meta.getStatus();
            var lifetimeBudget = meta == null ? null : meta.getLifetimeBudget();
            var budgetRemaining = meta == null ? null : meta.getBudgetRemaining();
            var endsAt = meta == null || meta.getEndsAt() == null ? null : meta.getEndsAt().toLocalDate();
            var ended = status != null && !"ACTIVE".equals(status);

            var cpm = impressions > 0
                    ? spend.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            var cpc = clicks > 0
                    ? spend.divide(BigDecimal.valueOf(clicks), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            campaignRows.add(new CampaignRow(campaignId, campaignName, status, spend,
                    impressions, clicks, reach, cpm, cpc, lifetimeBudget, budgetRemaining, endsAt, ended));

            totalSpend = totalSpend.add(spend);
            totalImpressions += impressions;
            totalClicks += clicks;
            totalReach += reach;
            if (budgetRemaining != null) totalBudgetRemaining = totalBudgetRemaining.add(budgetRemaining);
        }
        campaignRows.sort((left, right) -> right.spend().compareTo(left.spend()));

        var overallCpm = totalImpressions > 0
                ? totalSpend.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(totalImpressions), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        var overallCpc = totalClicks > 0
                ? totalSpend.divide(BigDecimal.valueOf(totalClicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new MetaSection(true, CURRENCY, totalSpend, totalImpressions, totalClicks, totalReach,
                overallCpm, overallCpc, totalBudgetRemaining, campaignRows);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
