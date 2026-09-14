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
        List<CampaignLine> byCampaign,
        AdSpendSummary adSpend) {

    /** Signup → verified email → uploaded first receipt (activated) → PRO tier, with conversion rates in [0,1]. */
    public record Funnel(long signups, long verified, long activated, long proTier,
                         double verifiedRate, double activatedRate, double proRate) {
    }

    /** One day of the signup series; {@code byChannel} maps AcquisitionChannel name → count that day. */
    public record DailySignupLine(LocalDate date, long total, Map<String, Long> byChannel) {
    }

    public record ChannelLine(String channel, long signups, long verified, long proTier) {
    }

    /** {@code adSpend}/{@code costPerSignup} are filled only when a Meta campaign name matches this utm_campaign. */
    public record CampaignLine(String source, String medium, String campaign,
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
                                 List<CampaignSpendLine> byCampaign, String note) {
    }

    public record CampaignSpendLine(String campaignId, String campaignName, BigDecimal spend,
                                    long clicks, long impressions) {
    }
}
