package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Admin marketing dashboard: Meta Ads campaign performance + Google Search Console
 * organic metrics for a given window. Both sections carry a {@code configured} flag —
 * false until the respective env vars are set, letting the FE show a setup callout.
 */
public record MarketingDashboardResponse(
        int windowDays,
        LocalDate from,
        LocalDate to,
        MetaSection meta,
        GscReportResponse organic) {

    /** Paid channel (Meta Ads) performance for the window. */
    public record MetaSection(
            boolean configured,
            String currency,
            BigDecimal totalSpend,
            long totalImpressions,
            long totalClicks,
            long totalReach,
            BigDecimal cpm,
            BigDecimal cpc,
            BigDecimal totalBudgetRemaining,
            List<CampaignRow> campaigns) {
    }

    /** One campaign's aggregated metrics + current status snapshot. */
    public record CampaignRow(
            String id,
            String name,
            String status,
            BigDecimal spend,
            long impressions,
            long clicks,
            long reach,
            BigDecimal cpm,
            BigDecimal cpc,
            BigDecimal lifetimeBudget,
            BigDecimal budgetRemaining,
            LocalDate endsAt,
            boolean ended) {
    }
}
