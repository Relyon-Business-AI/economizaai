package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.dto.response.AcquisitionReportResponse.DailySignupLine;
import com.relyon.economizaai.dto.response.AdminOverviewResponse.StateDistributionRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the admin "Visão Geral" screen shows, in one call and for one date window
 * (7 days, 30 days, all time or a custom from/to). Window bounds follow the Brasília day and
 * apply to {@code createdAt} — when the signup or the receipt entered the app.
 */
public record AdminDashboardResponse(
        Window window,
        UserStats users,
        ReceiptStats receipts,
        SpendStats spend,
        Engagement engagement,
        PriceIndex priceIndex,
        SignupFunnel funnel,
        /** One point per day of the window; {@code byChannel} maps AcquisitionChannel name → signups. */
        List<DailySignupLine> timeline,
        /** Confirmed receipts, confirmed spend and users (predominant UF) of the window, by receipts desc. */
        List<StateDistributionRow> byState,
        List<AdminUserSummaryResponse> recentUsers,
        List<AdminReceiptSummaryResponse> recentReceipts) {

    /** The resolved window: {@code period} is 7d | 30d | all | custom; both dates are inclusive. */
    public record Window(String period, LocalDate from, LocalDate to, long days) {
    }

    public record UserStats(long total, long inPeriod) {
    }

    /** {@code parseRate} = parsed OK ÷ (parsed + failed) among the window's receipts, in [0,1]. */
    public record ReceiptStats(long total, long inPeriod, double parseRate) {
    }

    /** Confirmed spend: all time and of the receipts that entered the app in the window. */
    public record SpendStats(BigDecimal total, BigDecimal inPeriod) {
    }

    /**
     * Distinct households with at least one receipt: across the window, and on the last day (DAU),
     * last 7 days (WAU) and last 30 days (MAU) ending at the window's end date.
     */
    public record Engagement(long activeHouseholdsInPeriod, long activeHouseholds1d, long activeHouseholds7d,
                             long activeHouseholds30d, double stickiness) {
    }

    /** Collaborative index size. Global on purpose: the index is anonymized and has no user to filter by. */
    public record PriceIndex(long priceObservations, long contributingHouseholds) {
    }

    public record SignupFunnel(long signups, long verified, long activated, double verifiedRate, double activatedRate) {
    }
}
