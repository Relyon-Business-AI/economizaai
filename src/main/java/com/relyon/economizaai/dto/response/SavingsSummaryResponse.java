package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;

/**
 * The household's realized savings attributable to surfaced deals (Phase D
 * north-star). {@code totalSavings} sums the R$ saved across all server-attributed
 * {@code CONVERTED} events for the household's users; {@code last30DaysSavings} is
 * the same restricted to the recent period (the "recent savings" setting, published to the
 * apps as {@code savingsRecentDays}; 30 days unless changed in the admin). {@code conversions} is the lifetime
 * count of attributed purchases.
 */
public record SavingsSummaryResponse(
        BigDecimal totalSavings,
        long conversions,
        BigDecimal last30DaysSavings
) {}
