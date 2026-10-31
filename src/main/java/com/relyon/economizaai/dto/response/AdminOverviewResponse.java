package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

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
        /** Distinct active households: today (DAU), 7 days (WAU), 30 days (MAU). */
        long activeHouseholds1d,
        long activeHouseholds7d,
        long activeHouseholds30d,
        /** DAU ÷ MAU — how "sticky" the product is (share of monthlies who use it daily). */
        double stickiness,
        /** Global confirmed spend across all households (the collaborative total). */
        BigDecimal totalSpendConfirmed,
        /** Price-index size + how many households ever contributed. */
        long priceObservations,
        long contributingHouseholds,
        /** Distribuição por estado (UF): notas confirmadas, gasto e usuários. Ordenado por notas desc. */
        List<StateDistributionRow> byState) {

    /** Uma UF no corte geográfico: notas confirmadas, gasto confirmado e nº de usuários (UF predominante). */
    public record StateDistributionRow(String uf, long receipts, BigDecimal spend, long users) {
    }
}
