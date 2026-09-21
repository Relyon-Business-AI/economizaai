package com.relyon.economizai.dto.response;

import java.math.BigDecimal;

/**
 * Cross-area snapshot for the admin home: the top-line numbers from every part
 * of the product in one call — users, receipts (with parse health), engagement,
 * the collaborative spend total and the price-index size. Excludes internal/test
 * accounts from user counts (same rule as the acquisition dashboard).
 */
public record AdminOverviewResponse(
        long usersTotal,
        long usersToday,
        long usersThisWeek,
        long usersPro,
        long usersPayingActive,
        long receiptsTotal,
        long receiptsToday,
        /** Parsed OK ÷ (parsed + failed) over the last 30 days, in [0,1]. */
        double parseRate30d,
        /** Distinct households that scanned in the last 7 days. */
        long activeHouseholds7d,
        /** Global confirmed spend across all households (the collaborative total). */
        BigDecimal totalSpendConfirmed,
        /** Price-index size + how many households ever contributed. */
        long priceObservations,
        long contributingHouseholds) {
}
