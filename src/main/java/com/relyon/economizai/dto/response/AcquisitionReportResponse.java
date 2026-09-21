package com.relyon.economizai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The acquisition dashboard payload: how many users signed up over a window,
 * where they came from (derived channel + raw campaign), how far they got down
 * the funnel, and — when the Meta integration is configured — what the paid
 * traffic cost. Money is BRL, scaled to 2 decimals.
 */
public record AcquisitionReportResponse(
        int windowDays,
        LocalDate from,
        LocalDate to,
        Funnel funnel,
        List<DailySignupLine> timeline,
        List<ChannelLine> byChannel,
        List<PlatformLine> byPlatform,
        List<CampaignLine> byCampaign,
        AdSpendSummary adSpend,
        RevenueSummary revenue,
        VisitSummary visits,
        List<RetentionLine> retention) {

    /** Signup → verified email → uploaded first receipt (activated) → PRO tier, with conversion rates in [0,1]. */
    public record Funnel(long signups, long verified, long activated, long proTier,
                         double verifiedRate, double activatedRate, double proRate) {
    }

    /** One day of the signup series; {@code byChannel} maps AcquisitionChannel name → count that day. */
    public record DailySignupLine(LocalDate date, long total, Map<String, Long> byChannel) {
    }

    public record ChannelLine(String channel, long signups, long verified, long proTier) {
    }

    /** Where the signup happened: WEB / ANDROID / IOS (or UNKNOWN when the client didn't send a platform). */
    public record PlatformLine(String platform, long signups) {
    }

    /**
     * {@code channel} is the derived AcquisitionChannel this campaign rolls up under
     * (lets the FE nest campaigns inside each channel). {@code adSpend}/{@code costPerSignup}
     * are filled only when a Meta campaign name matches this utm_campaign.
     */
    public record CampaignLine(String channel, String source, String medium, String campaign,
                               long signups, long verified, long proTier,
                               BigDecimal adSpend, BigDecimal costPerSignup) {
    }

    /**
     * Meta ad-spend rollup for the window. {@code configured} is false until the
     * Meta env vars are set — then the FE shows a "connect Meta Ads" hint instead
     * of zeros. {@code costPerSignup}/{@code costPerActivated} are spend ÷ the
     * INSTAGRAM_PAID signups (and activations) in the same window.
     */
    public record AdSpendSummary(boolean configured, String currency, BigDecimal totalSpend,
                                 long paidSignups, BigDecimal costPerSignup, BigDecimal costPerActivated,
                                 BigDecimal budgetRemaining, List<CampaignSpendLine> byCampaign, String note) {
    }

    /**
     * Per-campaign spend plus the current budget/status snapshot (from meta_campaign,
     * synced from Meta). {@code status} is Meta's effective_status; {@code ended} is
     * true when the campaign is no longer active; {@code endsAt} is its stop date.
     * Budget fields are null when Meta isn't configured or hasn't synced yet.
     */
    public record CampaignSpendLine(String campaignId, String campaignName, BigDecimal spend,
                                    long clicks, long impressions,
                                    String status, BigDecimal lifetimeBudget, BigDecimal budgetRemaining,
                                    LocalDate endsAt, boolean ended) {
    }

    /**
     * Money view. {@code realizedRevenue} is REAL money from provider events in the
     * window (starts near zero — grows as RevenueCat sends purchases). The rest is
     * MODELED from the configured PRO price: {@code mrrProxy} = all PRO users ×
     * monthly price (potential MRR if every PRO paid), {@code ltvPerProUser} =
     * monthly × assumedLifetimeMonths, {@code projectedLtv} = PRO signups in window
     * × that. {@code roas} = realized ÷ spend; {@code projectedRoas} = projectedLtv
     * ÷ spend; {@code ltvToCac} = ltvPerProUser ÷ cost-per-paid-signup. Null ROAS
     * means no spend in the window.
     *
     * <p>{@code payingCustomers} is the count of genuinely paying (non-promo) ACTIVE
     * subscriptions; {@code avgTicket} is the average realized revenue per paying
     * customer in the window (realizedRevenue ÷ payingCustomers). Both are 0 until
     * billing goes live — wired now so the number populates automatically once real
     * payments arrive.
     */
    public record RevenueSummary(String currency, boolean revenueRealized, int assumedLifetimeMonths,
                                 BigDecimal monthlyPrice, BigDecimal realizedRevenue,
                                 long payingCustomers, BigDecimal avgTicket, BigDecimal mrrProxy,
                                 BigDecimal ltvPerProUser, BigDecimal projectedLtv,
                                 BigDecimal roas, BigDecimal projectedRoas, BigDecimal ltvToCac,
                                 List<ChannelRevenueLine> byChannel, String note) {
    }

    /** Per-channel money: PRO signups in window, their realized revenue, and modeled LTV. */
    public record ChannelRevenueLine(String channel, long proUsers,
                                     BigDecimal realizedRevenue, BigDecimal projectedLtv) {
    }

    /**
     * First-party top of funnel: anonymous landing visits vs signups in the window,
     * so we own the click→signup conversion independent of Meta. {@code note} is set
     * when no visits have been recorded yet (beacon not live / no traffic).
     */
    public record VisitSummary(long totalVisits, long uniqueVisitors, long signups,
                               double visitToSignupRate, List<VisitCampaignLine> byCampaign, String note) {
    }

    public record VisitCampaignLine(String channel, String source, String medium, String campaign,
                                    long uniqueVisitors, long signups, double conversionRate) {
    }

    /**
     * Per-channel cohort quality: of the signups in the window, how many activated
     * (uploaded a receipt), and D7/D30 retention. Retention denominators only count
     * users old enough to have had the full 7-/30-day window, so a fresh signup
     * doesn't drag the rate down. Rates are in [0,1].
     */
    public record RetentionLine(String channel, long cohort, long activated, double activationRate,
                                long cohort7, long retainedD7, double retentionD7,
                                long cohort30, long retainedD30, double retentionD30) {
    }
}
